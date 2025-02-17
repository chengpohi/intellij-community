package com.intellij.settingsSync

import com.intellij.settingsSync.SettingsSnapshot.AppInfo
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.util.io.createDirectories
import com.intellij.util.io.createFile
import com.intellij.util.io.readText
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.div
import kotlin.io.path.writeText


@RunWith(JUnit4::class)
internal class GitSettingsLogTest {

  private val tempDirManager = TemporaryDirectory()
  private val appRule = ApplicationRule()
  private val disposableRule = DisposableRule()
  @Rule @JvmField val ruleChain: RuleChain = RuleChain.outerRule(tempDirManager).around(appRule).around(disposableRule)

  private lateinit var configDir: Path
  private lateinit var settingsSyncStorage: Path

  @Before
  fun setUp() {
    val mainDir = tempDirManager.createDir()
    configDir = mainDir.resolve("rootconfig").createDirectories()
    settingsSyncStorage = configDir.resolve("settingsSync")
  }

  @Test
  fun `copy files initially`() {
    val keymapContent = "keymapContent"
    val keymapsFolder = configDir / "keymaps"
    (keymapsFolder / "mykeymap.xml").createFile().writeText(keymapContent)
    val editorContent = "editorContent"
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText(editorContent)

    val settingsLog = initializeGitSettingsLog(keymapsFolder, editorXml)

    settingsLog.collectCurrentSnapshot().assertSettingsSnapshot {
      fileState("keymaps/mykeymap.xml", keymapContent)
      fileState("options/editor.xml", editorContent)
    }
  }

  @Test
  fun `merge conflict should be resolved as last modified`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val settingsLog = initializeGitSettingsLog(editorXml)

    settingsLog.applyIdeState(
      settingsSnapshot {
        fileState("options/editor.xml", "ideEditorContent")
      }, "Local changes"
    )
    settingsLog.applyCloudState(
      settingsSnapshot {
        fileState("options/editor.xml", "cloudEditorContent")
      }, "Remote changes"
    )

    settingsLog.advanceMaster()

    assertEquals("Incorrect content", "cloudEditorContent", (settingsSyncStorage / "options" / "editor.xml").readText())
    assertMasterIsMergeOfIdeAndCloud()
  }

  @Test
  fun `delete-modify merge conflict should be resolved as last modified`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val settingsLog = initializeGitSettingsLog(editorXml)

    settingsLog.applyIdeState(
      settingsSnapshot {
        fileState(FileState.Deleted("options/editor.xml"))
      }, "Local changes"
    )
    settingsLog.applyCloudState(
      settingsSnapshot {
        fileState("options/editor.xml", "cloudEditorContent")
      }, "Remote changes"
    )
    settingsLog.advanceMaster()

    assertEquals("Incorrect content", "cloudEditorContent", (settingsSyncStorage / "options" / "editor.xml").readText())
    assertMasterIsMergeOfIdeAndCloud()

  }

  @Test
  fun `modify-delete merge conflict should be resolved as last modified`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val settingsLog = initializeGitSettingsLog(editorXml)

    settingsLog.applyCloudState(
      settingsSnapshot {
        fileState("options/editor.xml", "moreCloudEditorContent")
      }, "Remote changes"
    )
    settingsLog.applyIdeState(
      settingsSnapshot {
        fileState(FileState.Deleted("options/editor.xml"))
      }, "Local changes"
    )
    settingsLog.advanceMaster()

    assertEquals("Incorrect deleted file content", DELETED_FILE_MARKER, (settingsSyncStorage / "options" / "editor.xml").readText())
    assertMasterIsMergeOfIdeAndCloud()
  }

  @Test
  fun `date of the snapshot`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val settingsLog = initializeGitSettingsLog(editorXml)

    val instant = Instant.ofEpochSecond(100500)
    settingsLog.applyCloudState(
      settingsSnapshot(SettingsSnapshot.MetaInfo(instant, AppInfo(UUID.randomUUID(), "", "", ""))) {
        fileState("options/editor.xml", "moreCloudEditorContent")
      }, "Remote changes"
    )
    settingsLog.advanceMaster()

    val snapshot = settingsLog.collectCurrentSnapshot()
    assertEquals("The date of the snapshot incorrect", instant, snapshot.metaInfo.dateCreated)
  }

  @Test fun `setBranchPosition should reset the working tree as well`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val settingsLog = initializeGitSettingsLog(editorXml)

    val masterPosition = settingsLog.getMasterPosition()

    settingsLog.applyCloudState(
      settingsSnapshot(SettingsSnapshot.MetaInfo(Instant.ofEpochSecond(100500), AppInfo(UUID.randomUUID(), "", "", ""))) {
        fileState("options/editor.xml", "moreCloudEditorContent")
      }, "Remote changes"
    )
    settingsLog.setCloudPosition(masterPosition)

    assertEquals(masterPosition, settingsLog.getCloudPosition()) // this is just a safety-check that setCloudPosition set the label correctly
    assertEquals("editorContent", (settingsSyncStorage / "options"/ "editor.xml").readText()) // this is real test that the cloud changes have gone away
  }

  @Test fun `collectCurrentSnapshot should take the master content`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val settingsLog = initializeGitSettingsLog(editorXml)

    val editorXmlFileState = "options/editor.xml"
    settingsLog.applyCloudState(
      settingsSnapshot(SettingsSnapshot.MetaInfo(Instant.ofEpochSecond(100500), AppInfo(UUID.randomUUID(), "", "", ""))) {
        fileState(editorXmlFileState, "moreCloudEditorContent")
      }, "Remote changes"
    )

    val snapshot = settingsLog.collectCurrentSnapshot()
    val actualFileState = snapshot.fileStates.find { it.file == editorXmlFileState } as FileState.Modified
    assertEquals("editorContent", String(actualFileState.content))
  }

  //@Test
  // todo requires a more previse merge conflict strategy implementation
  @Suppress("unused")
  fun `merge conflict should be resolved as last modified for the particular file`() {
    val editorXml = (configDir / "options" / "editor.xml").createFile()
    editorXml.writeText("editorContent")
    val lafXml = (configDir / "options" / "laf.xml").createFile()
    editorXml.writeText("lafContent")
    val settingsLog = initializeGitSettingsLog(lafXml, editorXml)

    settingsLog.applyIdeState(
      settingsSnapshot {
        fileState("editor.xml", "ideEditorContent")
      }, "Local changes"
    )
    settingsLog.applyCloudState(
      settingsSnapshot {
        fileState("laf.xml", "cloudLafContent")
      }, "Remote changes"
    )
    settingsLog.applyCloudState(
      settingsSnapshot {
        fileState("editor.xml", "cloudEditorContent")
      }, "Remote changes"
    )
    settingsLog.applyIdeState(
      settingsSnapshot {
        fileState("laf.xml", "ideEditorContent")
      }, "Local changes"
    )

    settingsLog.advanceMaster()

    assertEquals("Incorrect content", "cloudEditorContent", editorXml.readText())
    assertEquals("Incorrect content", "ideLafContent", lafXml.readText())
    assertMasterIsMergeOfIdeAndCloud()
  }

  private fun initializeGitSettingsLog(vararg filesToCopyInitially: Path): GitSettingsLog {
    val settingsLog = GitSettingsLog(settingsSyncStorage, configDir, disposableRule.disposable) {
      filesToCopyInitially.toList()
    }
    settingsLog.initialize()
    settingsLog.logExistingSettings()
    val masterPosition = settingsLog.advanceMaster()
    settingsLog.setCloudPosition(masterPosition)
    return settingsLog
  }

  private fun assertMasterIsMergeOfIdeAndCloud() {
    val dotGit = settingsSyncStorage.resolve(".git")
    FileRepositoryBuilder.create(dotGit.toFile()).use { repository ->
      val walk = RevWalk(repository)
      try {
        val commit: RevCommit = walk.parseCommit(repository.findRef("master").objectId)
        walk.markStart(commit)
        val parents = commit.parents
        assertEquals(2, parents.size)
        val ide = repository.findRef("ide")!!
        val cloud = repository.findRef("cloud")!!
        val (parent1, parent2) = parents
        if (parent1.id == ide.objectId) {
          assertTrue(parent2.id == cloud.objectId)
        }
        else if (parent1.id == cloud.objectId) {
          assertTrue(parent2.id == ide.objectId)
        }
        else {
          fail("Neither ide nor cloud are parents of master")
        }
        walk.dispose()
      }
      finally {
        walk.dispose()
        walk.close()
      }
    }
  }
}