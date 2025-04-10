package com._24i

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.TaskAction
import java.util.regex.Pattern
import java.lang.Boolean.parseBoolean as javaParseBoolean

open class TaskGitVersion : DefaultTask() {
    private var branch: String? = ""
    private var parentBranch: String? = ""
    private var closestHighestTagHash: String? = null
    private var closestTag: String? = null
    private var currentShortCommitHash: String? = null
    private var currentCommitHash: String? = null
    private var mavenVersion: String? = null
    private var appVersion: String? = null
    private var gitDescribe: String? = null
    private var gitAppDescribe: String? = null
    private var gitPaddedVersionCount: String? = null
    private var snapshot: Boolean = true
    private var initialized: Boolean = false

    private fun parseBoolean(input: String, default: Boolean = false): Boolean = runCatching {
        javaParseBoolean(input)
    }.onFailure { e ->
        println(e.message)
    }.getOrDefault(default)

    private fun execGitCommand(vararg commands: Any): String? {
        logger.debug("cmd: ${commands.contentToString()}")
        try {
            return project.providers.exec {
                commandLine(*commands)
            }.standardOutput.asText.getOrElse("").trim { it <= ' ' }
        } catch (e: Exception) {
            logger.debug("E: ${e.message}")
            return null
        }
    }

    override fun configure(closure: Closure<*>): Task {
        findGitVersions()
        setVersions()
        return super.configure(closure)
    }

    @TaskAction
    fun findGitVersions() {
        logger.debug("main task action")
        findBranch()
        findCurrentCommitHash()
        if (branch != "HEAD") {
            findParentBranch()
        }
        findCurrentCommitShortHash()
        findClosestTagHash()
        findGitClosestTag()
        findVersion()
        findGitDescribeVersion()
        findGitAppDescribeVersion()
        setVersions()
    }

    private fun isProjectDirty(): Boolean {
        val resultValue = execGitCommand("git", "status", "--porcelain")
        return if (resultValue != null && resultValue.length > 2) {
            resultValue.split("\n".toRegex()).dropLastWhile {
                it.isEmpty()
            }.toTypedArray().isNotEmpty()
        } else {
            false
        }
    }

    private fun findParentBranch() {
        logger.debug("findParentBranch of branch: $branch")
        if (branch != null && (branch != "main") && !branch!!.startsWith("bugfix_")) {
            var foundHash: String? = parentBranchCommitHash()
            if (foundHash.isNullOrEmpty()) {
                foundHash = currentCommitHash
            }
            if (!foundHash.isNullOrEmpty()) {
                val hashes = foundHash.split(" ".toRegex()).dropLastWhile {
                    it.isEmpty()
                }.toTypedArray()
                var parentBranchFound = ""
                for (hash in hashes) {
                    val foundBranch = findLowestBranchForHash(hash)
                    if (foundBranch.isNotEmpty() && (parentBranchFound != foundBranch)) {
                        if (foundBranch.startsWith("bugfix")) {
                            parentBranchFound = findLowestBranch(foundBranch, parentBranchFound)
                        } else if (!parentBranchFound.startsWith("bugfix") && foundBranch == "main") {
                            parentBranchFound = foundBranch
                        }
                    }
                }
                parentBranch = parentBranchFound
            }
        }
        logger.debug("Parent branch: $parentBranch")
    }

    private fun findLowestBranch(branchA: String, branchB: String): String {
        logger.debug("findLowestBranch of ($branchA, $branchB)")
        if (branchA == "") return branchB
        if (branchB == "") return branchA
        if (branchA.startsWith("bugfix") && branchB.startsWith("bugfix")) {
            val compare = branchA.compareTo(branchB)
            return if (compare < 0 || compare == 0) branchA else branchB
        } else if (branchA.startsWith("bugfix")) {
            return branchA
        } else if (branchB.startsWith("bugfix")) {
            return branchB
        }
        if (branchA == "main") return branchA
        if (branchB == "main") return branchB
        return ""
    }

    private fun findLowestBranchForHash(hash: String): String {
        logger.debug("findLowestBranchForHash: $hash")
        val hasCI = project.hasProperty("CI")
        val isCI = if (hasCI) parseBoolean(project.property("CI").toString()) else false
        val outputString = if (isCI) {
            execGitCommand("git", "branch", "--contains", hash)
        } else {
            execGitCommand("git", "branch", "-r", "--contains", hash)
        }
        if (outputString == null) return ""
        val branches: String = outputString
        if (outputString.contains("\n")) {
            val branchesArray = outputString.split("\n").dropLastWhile {
                it.isEmpty()
            }.toTypedArray()
            var branchFound = ""
            for (item in branchesArray) {
                branchFound = if (item.startsWith("*")) item.substring(1) else item
                branchFound = item.trim { it <= ' ' }.replace("origin/", "")
                if (branchFound.startsWith("bugfix_") || branchFound == "main") {
                    return branchFound
                }
            }
        } else {
            if (branches.startsWith("*")) {
                return branches.replace("origin/", "")
                    .substring(1)
                    .trim { it <= ' ' }
            }
            return outputString.replace("origin/", "")
                .trim { it <= ' ' }
        }
        return ""
    }

    private fun parentBranchCommitHash(): String {
        logger.debug("parentBranchCommitHash")
        val hasCI = project.hasProperty("CI")
        val isCI = if (hasCI) parseBoolean(project.property("CI").toString()) else false
        val outputString = if (isCI) {
            execGitCommand("git", "log", branch!!, "--not", "main", "--pretty=format:%P")
        } else {
            execGitCommand("git", "log", branch!!, "--not", "origin/main", "--pretty=format:%P")
        }
        val hashes: Array<String>
        var version = ""
        if (outputString != null && outputString.contains("\n")) {
            hashes = outputString.split("\n").dropLastWhile {
                it.isEmpty()
            }.toTypedArray()
            for (item in hashes) version = item
        } else if (outputString != null) {
            version = outputString
        }
        return version
    }

    private fun setVersions() {
        if (parentBranch == null) {
            parentBranch = "N/A"
        }
        logger.debug("setVersions:")
        logger.debug("gitBranch : $branch")
        System.setProperty("gitBranch", branch)
        logger.debug("gitParentBranch : $parentBranch")
        System.setProperty("gitParentBranch", parentBranch)
        logger.debug("gitHighestTagHash : $closestHighestTagHash")
        System.setProperty("gitHighestTagHash", closestHighestTagHash)
        logger.debug("gitHighestTag : $closestTag")
        System.setProperty("gitHighestTag", closestTag)
        logger.debug("gitCurrentShortCommitHash : $currentShortCommitHash")
        System.setProperty("gitCurrentShortCommitHash", currentShortCommitHash)
        logger.debug("gitCurrentCommitHash : $currentCommitHash")
        System.setProperty("gitCurrentCommitHash", currentCommitHash)
        logger.debug("mavenVersion : $mavenVersion")
        System.setProperty("mavenVersion", mavenVersion)
        logger.debug("appVersion : $appVersion")
        System.setProperty("appVersion", appVersion)
        logger.debug("gitPaddedVersionCount : $gitPaddedVersionCount")
        if (gitPaddedVersionCount != null) {
            System.setProperty("gitPaddedVersionCount", gitPaddedVersionCount)
        }
        logger.debug("gitDescribe : $gitDescribe")
        System.setProperty("gitDescribe", gitDescribe)
        logger.debug("gitAppDescribe : $gitAppDescribe")
        System.setProperty("gitAppDescribe", gitAppDescribe)
        logger.debug("versionSnapshot : $snapshot")
        System.setProperty("versionSnapshot", "" + snapshot)
        logger.debug("project.version : $mavenVersion")
        if (mavenVersion != null) {
            project.version = mavenVersion!!
        }
    }

    private fun findCurrentCommitShortHash() {
        logger.debug("findCurrentCommitShortHash")
        currentShortCommitHash = execGitCommand("git", "rev-parse", "--short", "HEAD")
        if (currentShortCommitHash.isNullOrEmpty()) {
            currentShortCommitHash = "0"
        }
        logger.debug("Found currentShortCommitHash: $currentShortCommitHash")
    }

    private fun findCurrentCommitHash() {
        logger.debug("findCurrentCommitHash")
        currentCommitHash = execGitCommand("git", "rev-parse", "HEAD")
        if (currentCommitHash.isNullOrEmpty()) {
            currentCommitHash = "NoHashFound"
        }
        logger.debug("Found currentCommitHash: $currentCommitHash")
    }

    private fun findBranch() {
        logger.debug("findBranch")
        branch = execGitCommand("git", "rev-parse", "--abbrev-ref", "HEAD")
        branch = branch?.replace("[^\\dA-Za-z ]".toRegex(), "_")
        parentBranch = branch
        logger.debug("Found branch: $branch")
    }

    private fun findClosestTagHash() {
        logger.debug("findClosestTagHash")
        var branchToFindTag = branch
        if (parentBranch != branch) {
            branchToFindTag = parentBranch
        }
        logger.debug("branchToFindTag : $branchToFindTag")
        if (branchToFindTag == "main") {
            val tag = findGitHighestTag()
            closestHighestTagHash = execGitCommand("git", "log", "-1", "--format=format:%H", tag)
            closestTag = tag
        } else if (branchToFindTag != null) {
            if (branchToFindTag.startsWith("bugfix_")) {
                val version = highestVersionNumber(branchToFindTag)
                this.closestHighestTagHash = execGitCommand("git", "rev-list", "-n", "1", version)
            } else if (branchToFindTag == "HEAD") {
                closestHighestTagHash = currentCommitHash
                findGitClosestTag()
                if (
                    closestTag!!.contains("-") &&
                    !closestTag!!.contains("-RC") &&
                    !closestTag!!.contains("-M")
                ) {
                    val tag = closestTag!!.substring(0, closestTag!!.indexOf("-"))
                    this.closestHighestTagHash = execGitCommand("git", "rev-list", "-n", "1", tag)
                }
            } else {
                logger.debug("findClosestTagHash else ")
                val tag = findGitHighestTag()
                this.closestHighestTagHash =
                    execGitCommand("git", "log", "-1", "--format=format:%H", tag)
                this.closestTag = tag
            }
        }
        if (closestHighestTagHash == null || closestHighestTagHash!!.isEmpty()) {
            this.closestHighestTagHash = "0"
        }
        logger.debug("Found closestHighestTagHash: $closestHighestTagHash")
    }

    private fun highestVersionNumber(branch: String): String {
        logger.debug("highestVersionNumber for branch: $branch")
        val extractedVersion = branch.replace("bugfix_", "")
            .replace("_", ".")
        val outputString = execGitCommand(
            "git", "tag", "-l",
            "$extractedVersion*", "--sort=v:refname"
        )
        val hashes: Array<String>
        var version = "0.0.0"
        if (outputString!!.contains("\n")) {
            hashes = outputString.split("\n").dropLastWhile {
                it.isEmpty()
            }.toTypedArray()
            for (item in hashes) {
                if (item.matches("[0-9|.|a-z|R|C|M|-]*".toRegex())) {
                    if (
                        version.isEmpty() ||
                        !(item.startsWith(version) && (item.contains("RC") ||
                                item.contains("M")))
                    ) {
                        version = item
                    }
                }
            }
        } else {
            version = outputString
        }
        logger.debug("highestVersionNumber for branch: $branch = $version")
        return version
    }

    private fun findGitHighestTag(): String {
        logger.debug("findGitHighestTag")
        val outputString = execGitCommand("git", "tag", "-l", "--sort=v:refname")
        if (outputString.isNullOrEmpty()) return "0.0.0"
        val hashes = if (outputString.contains("\n")) {
            outputString.split("\n").dropLastWhile {
                it.isEmpty()
            }.toTypedArray()
        } else {
            arrayOf(outputString)
        }
        var closestTag = ""
        for (item in hashes) {
            if (item.matches("[0-9|.|a-z|R|C|M|-]*".toRegex())) {
                if (
                    closestTag.isEmpty() ||
                    !(item.startsWith(closestTag) && (item.contains("RC") ||
                            item.contains("M")))
                ) {
                    closestTag = item
                }
            }
        }
        logger.debug("findGitHighestTag : $closestTag")
        return closestTag
    }

    private fun findGitClosestTag() {
        logger.debug("findGitClosestTag")
        closestTag = execGitCommand("git", "describe", "--tags", closestHighestTagHash!!)
        if (closestTag.isNullOrEmpty()) closestTag = "0.0.0"
        branch = branch ?: "main"
        if (branch!!.startsWith("bugfix_") && closestTag == "0.0.0") {
            val extractedVersion = branch!!.replace("bugfix_", "")
                .replace("_", ".")
            closestTag = "$extractedVersion.0"
        }
        logger.debug("Found ClosestTag: $closestTag")
    }

    private fun findVersion() {
        logger.debug("findVersion")
        val closestTag = closestTag
        var gitBranch = branch
        val versionSplit = Pattern.compile("([0-9]+).([0-9]+).([0-9]+).*")
        val matcher = versionSplit.matcher(closestTag.toString())
        if (closestHighestTagHash == currentCommitHash) {
            mavenVersion = closestTag
            if (matcher.find()) {
                val major = matcher.group(1)
                val minor = matcher.group(2).toLong().let { String.format("%02d", it) }
                val bugfix = matcher.group(3).toLong().let { String.format("%02d", it) }
                gitPaddedVersionCount = major + minor + bugfix
            }
            snapshot = false
            if (isProjectDirty()) {
                mavenVersion += "-dirty"
            }
        } else {
            if (matcher.find()) {
                var major = matcher.group(1)
                var minor = matcher.group(2)
                var bugfix = matcher.group(3)
                var branchToFindVersion = branch
                if (parentBranch != branch) {
                    branchToFindVersion = parentBranch
                }
                if (gitBranch == null) {
                    mavenVersion = "0.0.0-SNAPSHOT"
                    branch = "unknown"
                    return
                }
                if (gitBranch == "main") {
                    if (closestTag!!.contains("-M")) {
                        minor = "0"
                        bugfix = "0.0-SNAPSHOT"
                    } else {
                        major = (major.toLong() + 1).toString()
                        minor = "0"
                        bugfix = "0-SNAPSHOT"
                    }
                } else if (gitBranch.startsWith("bugfix")) {
                    if ((closestHighestTagHash == "0" && minor == "0") || closestTag!!.contains("-RC") || closestTag.contains(
                            "-M"
                        )
                    ) {
                        minor = "0"
                        bugfix = "$bugfix-SNAPSHOT"
                    } else {
                        minor = (minor.toLong() + 1).toString()
                        bugfix = "0-SNAPSHOT"
                    }
                } else {
                    var startIdx = 0
                    val endIdx = 11
                    if (gitBranch == null) {
                        gitBranch = "-UNKNOWN"
                    } else if (gitBranch.length >= endIdx) {
                        if (gitBranch.startsWith("SPRINT-")) {
                            startIdx = 7
                        }
                        gitBranch = "-" + gitBranch.substring(startIdx, endIdx)
                    } else if (gitBranch == "HEAD") {
                        gitBranch = ""
                    } else {
                        gitBranch = "-" + gitBranch.substring(startIdx, gitBranch.length)
                    }
                    if (parentBranch != branch && parentBranch!!.startsWith("bugfix")) {
                        bugfix = (bugfix.toLong() + 1).toString() + gitBranch + "-SNAPSHOT"
                    } else if (gitBranch.startsWith("-") && closestTag!!.contains("-M")) {
                        bugfix = "0$gitBranch-SNAPSHOT"
                    } else {
                        minor = (minor.toLong() + 1).toString()
                        bugfix = "0$gitBranch-SNAPSHOT"
                    }
                }
                var bugfixExtracted = "0"
                if (bugfix.indexOf("-") > 0) {
                    bugfixExtracted = bugfix.substring(0, bugfix.indexOf("-"))
                } else if (bugfix.matches("\\d+".toRegex())) {
                    bugfixExtracted = bugfix
                }
                gitPaddedVersionCount = major + String.format("%02d", minor.toLong()) + String.format("%02d", bugfixExtracted.toLong())
                mavenVersion = "$major.$minor.$bugfix"
                if (isProjectDirty()) {
                    mavenVersion += "-dirty"
                }
            }
        }
        if (mavenVersion == closestTag && branch == "HEAD") {
            branch = mavenVersion
        }
        logger.debug("Found mavenVersion: $mavenVersion")
        logger.debug("Found gitPaddedVersionCount: $gitPaddedVersionCount")
    }

    private fun findGitDescribeVersion() {
        logger.debug("findGitDescribeVersion")
        gitDescribe = if (currentCommitHash == closestHighestTagHash) {
            closestTag
        } else {
            "$mavenVersion-$currentShortCommitHash"
        }
        logger.debug("found gitDescribe: $gitDescribe")
    }

    private fun findGitAppDescribeVersion() {
        logger.debug("findGitAppDescribeVersion")
        gitAppDescribe = if (currentCommitHash == closestHighestTagHash) closestTag
        else gitDescribe!!.replace("-SNAPSHOT", "")
        appVersion = mavenVersion!!.replace("-SNAPSHOT", "")
        logger.debug("found gitAppDescribe: $gitAppDescribe")
    }

    private fun compareVersions(v1: String, v2: String): Int {
        if (v1.isNotEmpty() && v2.isEmpty()) return -1
        if (v1.isEmpty() && v2.isEmpty()) return 0
        if (v1.isEmpty() && v2.isNotEmpty()) return 1
        val pos1 = v1.indexOf('.')
        val pos2 = v2.indexOf('.')
        val num1 = (if (pos1 > 0) v1.substring(0, pos1).toInt() else 0)
        val num2 = (if (pos2 > 0) v2.substring(0, pos2).toInt() else 0)
        if (num1 !== num2) return num1.compareTo(num2)
        val tail1 = (if (pos1 > 0) v1.substring(pos1 + 1) else "")
        val tail2 = (if (pos2 > 0) v2.substring(pos2 + 1) else "")
        return compareVersions(tail1, tail2)
    }
}