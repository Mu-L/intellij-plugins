package org.jetbrains.qodana.jvm.maven

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.workspaceModel.ide.JpsProjectLoadingManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.utils.MavenSimpleProjectComponent
import org.jetbrains.idea.maven.utils.MavenUtil
import org.jetbrains.qodana.staticAnalysis.inspections.config.QodanaConfig
import org.jetbrains.qodana.staticAnalysis.workflow.QodanaWorkflowExtension


class QodanaMavenReimporter : QodanaWorkflowExtension {
  companion object {
    val LOG = Logger.getInstance(QodanaMavenReimporter::class.java)
  }

  override suspend fun afterConfiguration(config: QodanaConfig, project: Project) {
    val forceReimport = java.lang.Boolean.getBoolean("qodana.trigger.maven.import")
    if (MavenSimpleProjectComponent.isNormalProjectInHeadless() && !forceReimport) {
      return
    }

    val projectsManager = MavenProjectsManager.getInstance(project)
    val mavenProjects = projectsManager.projects

    val alreadyImported = project.getUserData(ExternalSystemDataKeys.NEWLY_CREATED_PROJECT) == true

    if (!alreadyImported && !mavenProjects.isEmpty()) {
        reimportMavenProjects(mavenProjects, project, projectsManager)
    }
    else {
      if (forceReimport) {
        LOG.info("Re-importing maven project after configuration")
        if (mavenProjects.isEmpty()) {
          LOG.info("Trying to re-import the project as maven")
          importProject(project)
          LOG.info("Maven re-import finished")
          if (projectsManager.projects.isEmpty()) {
            LOG.info("No maven projects were imported")
          }
          else {
            LOG.info("Consider projects as reimported")
          }
        } else {
          reimportMavenProjects(mavenProjects, project, projectsManager)
        }
      }
    }

    LOG.info("Await end of dumb mode")
    val futureDumb = CompletableDeferred<Unit>()
    DumbService.getInstance(project).runWhenSmart {
      futureDumb.complete(Unit)
    }
    futureDumb.await()
    LOG.info("Now running in smart mode")
  }


  private suspend fun reimportMavenProjects(
    mavenProjects: List<MavenProject>,
    project: Project,
    projectsManager: MavenProjectsManager,
  ) {
    LOG.info("Re-importing maven project after configuration: ${mavenProjects.map { it.displayName }}")
    val future = CompletableDeferred<Unit>()
    val disposable = Disposer.newDisposable("QodanaMavenReimporterDisposable")
    MavenProjectsManager.getInstance(project).addManagerListener(object : MavenProjectsManager.Listener {
      override fun projectImportCompleted() {
        future.complete(Unit)
      }
    }, disposable)
    LOG.info("Scheduling force update maven project")
    projectsManager.scheduleForceUpdateMavenProjects(mavenProjects)
    LOG.info("Awaiting for maven project import completion")
    future.await()
    LOG.info("Maven project reimport completed")
    Disposer.dispose(disposable)
  }

  private suspend fun importProject(project: Project) {
    suspendCancellableCoroutine { continuation ->
      ExternalProjectsManagerImpl.getInstance(project).runWhenInitialized {
        continuation.resumeWith(Result.success(Unit))
      }
    }

    suspendCancellableCoroutine { continuation ->
      MavenUtil.runWhenInitialized(project) {
        continuation.resumeWith(Result.success(Unit))
      }
    }

    suspendCancellableCoroutine { continuation ->
      JpsProjectLoadingManager.getInstance(project).jpsProjectLoaded {
        continuation.resumeWith(Result.success(Unit))
      }
    }

    val mavenManager = MavenProjectsManager.getInstance(project)
    if (!mavenManager.isMavenizedProject) {
      val files = mavenManager.collectAllAvailablePomFiles()
      mavenManager.addManagedFilesWithProfiles(files, MavenExplicitProfiles.NONE, null, null, true)
    }
    else {
      mavenManager.updateAllMavenProjects(MavenSyncSpec.full("ImportMavenProjectUtil"))
    }
  }
}


