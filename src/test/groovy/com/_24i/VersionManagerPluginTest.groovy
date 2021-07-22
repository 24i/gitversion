package com._24i

import org.junit.Test
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.Project
import static org.junit.Assert.*

class VersionManagerPluginTest {
    @Test
    public void findVersionPluginAddsFindVersionTaskToProject() {
        Project project = ProjectBuilder.builder().build()
        project.apply plugin: 'com.24i.gitVersion'

        assertTrue(project.tasks.version instanceof VersionManagerTask)
    }
}
