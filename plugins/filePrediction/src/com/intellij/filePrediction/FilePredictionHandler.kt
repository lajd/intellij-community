// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.filePrediction

import com.intellij.filePrediction.features.FilePredictionFeaturesHelper
import com.intellij.filePrediction.references.FilePredictionReferencesHelper
import com.intellij.filePrediction.features.history.FilePredictionHistory
import com.intellij.filePrediction.features.history.context.FilePredictionContext
import com.intellij.filePrediction.predictor.FileUsagePredictionHandler
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.impl.ProjectManagerImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.NonUrgentExecutor

class FilePredictionHandler {
  companion object {
    private const val CALCULATE_OPEN_FILE_PROBABILITY: Double = 0.5
    private const val CALCULATE_CANDIDATE_PROBABILITY: Double = 0.1

    fun getInstance(): FilePredictionHandler? = ServiceManager.getService(FilePredictionHandler::class.java)
  }

  private val predictor: FileUsagePredictionHandler = FileUsagePredictionHandler(50, 5, 10)

  private var session: FilePredictionSessionHolder = FilePredictionSessionHolder()

  fun onFileSelected(project: Project, newFile: VirtualFile, prevFile: VirtualFile?) {
    if (ProjectManagerImpl.isLight(project)) {
      return
    }

    NonUrgentExecutor.getInstance().execute {
      BackgroundTaskUtil.runUnderDisposeAwareIndicator(project, Runnable {
        val previousSession = session.getSession()
        if (previousSession != null && previousSession.shouldLog(CALCULATE_OPEN_FILE_PROBABILITY)) {
          logOpenedFile(project, previousSession.id, newFile, prevFile)
        }
        val newSession = session.newSession()
        if (newSession != null && newSession.shouldLog(CALCULATE_CANDIDATE_PROBABILITY)) {
          predictor.predictNextFile(project, newSession.id, newFile)
        }
        FilePredictionHistory.getInstance(project).onFileSelected(newFile.url)
      })
    }
    FilePredictionContext.getInstance(project).onFileSelected(newFile.url)
  }

  fun onFileOpened(project: Project, file: VirtualFile) {
    if (ProjectManagerImpl.isLight(project)) {
      return
    }

    FilePredictionContext.getInstance(project).onFileOpened(file.url)
  }

  fun onFileClosed(project: Project, file: VirtualFile) {
    if (ProjectManagerImpl.isLight(project)) {
      return
    }

    FilePredictionContext.getInstance(project).onFileClosed(file.url)
  }

  private fun logOpenedFile(project: Project,
                            sessionId: Int,
                            newFile: VirtualFile,
                            prevFile: VirtualFile?) {
    val start = System.currentTimeMillis()
    val result = FilePredictionReferencesHelper.calculateExternalReferences(project, prevFile)

    val features = FilePredictionFeaturesHelper.calculateFileFeatures(project, newFile, result.value, prevFile)
    val duration = System.currentTimeMillis() - start
    FileNavigationLogger.logEvent(
      project, "file.opened", sessionId, features, newFile.path, prevFile?.path, duration, result.duration
    )
  }
}