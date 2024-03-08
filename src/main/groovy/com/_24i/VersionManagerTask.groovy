package com._24i

import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecResult

class VersionManagerTask extends DefaultTask {
    @Internal
    String branch = "";
    @Internal
    String parentBranch = "";
    @Internal
    String closestHighestTagHash;
    @Internal
    String closestTag;
    @Internal
    String currentShortCommitHash;
    @Internal
    String currentCommitHash;
    @Internal
    String mavenVersion;
    @Internal
    String appVersion;
    @Internal
    String gitDescribe;
    @Internal
    String gitAppDescribe;
    @Internal
    String gitPaddedVersionCount;
    @Internal
    boolean snapshot = true;
    @Internal
    boolean initialized = false;
    @Internal
    Task superConfigureTask = null;

    @Override
    Task configure(Closure closure) {
        if (!initialized) {
            findGitVersions()
            setVersions()
            initialized = true;
            superConfigureTask = super.configure(closure)
        }
        return superConfigureTask;
    }

    @TaskAction
    def findGitVersions() {
        findBranch()
        findCurrentCommitHash()
        if (!branch.equals('HEAD')) {
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


    private String execGitCommand(Object... commands) {
        try {
            def stderr = new ByteArrayOutputStream()
            def stdout = new ByteArrayOutputStream()
            ExecResult result = this.project.exec({
                it.commandLine = commands
                it.standardOutput = stdout
                it.errorOutput = stderr;
            })
            return stdout.toString().trim()
        } catch (Exception e) {
            return null;
        }
    }

    private void findParentBranch() {
        if (branch != null && !branch.equals('main') && !branch.startsWith("bugfix_")) {
            def foundHash = parentBranchCommitHash()
            if (foundHash == null || foundHash.isEmpty()) {
                foundHash = currentCommitHash
            }
            if (foundHash != null && !foundHash.isEmpty()) {
                def hashes = foundHash.split(' ')
                def parentBranchFound = ''
                for (String hash : hashes) {
                    String foundBranch = findLowestBranchForHash(hash)
                    if (foundBranch != null && !foundBranch.isEmpty() && !parentBranchFound.equals(foundBranch)) {
                        if (foundBranch.startsWith('bugfix')) {
                            parentBranchFound = findLowestBranch(foundBranch,parentBranchFound)
                        } else if (!parentBranchFound.startsWith('bugfix') && foundBranch.equals('main')) {
                            parentBranchFound = foundBranch
                        }
                    }
                }
                parentBranch = parentBranchFound
            }
        }
    }

    private String findLowestBranch(String branchA, String branchB) {
        if (branchA.equals('')) {
            return branchB
        }
        if (branchB.equals('')) {
            return branchA
        }
        if (branchA.startsWith('bugfix') && branchB.startsWith('bugfix')) {
            int compare = branchA.compareTo(branchB)
            if (comare < 0 || compare == 0) {
                return branchA
            }
            else {
                return branchB
            }
        } else if (branchA.startsWith('bugfix')) {
            return branchA
        } else if (branchB.startsWith('bugfix')) {
            return branchB
        }
        if (branchA.equals('main')) {
            return branchA
        }
        if (branchB.equals('main')) {
            return branchB
        }
        return ''
    }

    private String findLowestBranchForHash(String hash) {
        def stderr = new ByteArrayOutputStream()
        def stdout = new ByteArrayOutputStream()
        String outputString;
        if (project.hasProperty('CI') && Boolean.valueOf(project.properties['CI'])) {
            outputString = execGitCommand('git', 'branch', '--contains', hash)
        } else {
            outputString = execGitCommand('git', 'branch', '-r', '--contains', hash)
        }
        if (outputString == null) {
            return ''
        }
        def branches = outputString;
        if (outputString.contains('\n')) {
            branches = outputString.split('\n');
            def branchFound = ''
            for (final String item : branches) {
                if (item.startsWith("*")) {
                    branchFound = item.substring(1);
                } else {
                    branchFound = item
                }
                branchFound = branchFound.trim().replaceAll('origin\\/','')
                if (branchFound.startsWith('bugfix_') || branchFound.equals('main')) {
                    return branchFound
                }
            }
        } else {
            if (branches.startsWith('*')) {
                return branches.replaceAll('origin/','').substring(1).trim()
            }
            return outputString.replaceAll('origin/','').trim()
        }
        return '';
    }

    private String parentBranchCommitHash() {
        def stderr = new ByteArrayOutputStream()
        def stdout = new ByteArrayOutputStream()

        def outputString;
        if (project.hasProperty('CI') && Boolean.valueOf(project.properties['CI'])) {
            outputString = execGitCommand( 'git', 'log', branch, '--not', 'main', '--pretty=format:%P')
        } else {
            outputString = execGitCommand( 'git', 'log', branch, '--not', 'origin/main', '--pretty=format:%P')
        }
        def hashes;
        def version = '';
        if (outputString != null && outputString.contains('\n')) {
            hashes = outputString.split('\n');
            for (String item : hashes) {
                version = item;
            }
        } else if (outputString != null) {
            version = outputString
        }
        return version;
    }



    void setVersions() {
        System.setProperty("gitBranch",branch);
        if (parentBranch == null) {
            parentBranch = 'N/A'
        }
        System.setProperty("gitParentBranch",parentBranch);
        System.setProperty("gitHighestTagHash",closestHighestTagHash);
        System.setProperty("gitHighestTag",closestTag);
        System.setProperty("gitCurrentShortCommitHash",currentShortCommitHash);
        System.setProperty("gitCurrentCommitHash",currentCommitHash);
        System.setProperty("mavenVersion",mavenVersion);
        System.setProperty("appVersion",appVersion);
        System.setProperty("gitDescribe",gitDescribe);
        System.setProperty("gitAppDescribe",gitAppDescribe);
        if (gitPaddedVersionCount != null) {
            System.setProperty("gitPaddedVersionCount", gitPaddedVersionCount);
        }

        System.setProperty("versionSnapshot",''+snapshot);
        if (mavenVersion != null) {
            getProject().version = mavenVersion
        }


    }

    void findCurrentCommitShortHash() {
        currentShortCommitHash = execGitCommand('git', 'rev-parse', '--short','HEAD')
        if (currentShortCommitHash == null || currentShortCommitHash.isEmpty()) {
            currentShortCommitHash = "0"
        }
        logger.debug("Found currentShortCommitHash: " + currentShortCommitHash)
    }

    void findCurrentCommitHash() {
        currentCommitHash = execGitCommand('git', 'rev-parse', 'HEAD')
        if (currentCommitHash == null || currentCommitHash.isEmpty()) {
            currentCommitHash = "NoHashFound";
        }
        logger.debug("Found currentCommitHash: " + currentCommitHash)
    }


    void findBranch() {
        branch = execGitCommand('git','rev-parse', '--abbrev-ref', 'HEAD')
        if (branch != null) {
            branch = branch.replaceAll("[^\\dA-Za-z ]", "_")
        }
        parentBranch = branch
        logger.debug("Found branch: " + branch)

    }

    void findClosestTagHash() {
            def branchToFindTag = branch
            if (!parentBranch.equals(branch)) {
                branchToFindTag = parentBranch
            }
            if (branchToFindTag.equals('main')) {
                logger.info("findClosestTagHash " + branchToFindTag)
                def tag = findGitHighestTag()
                closestHighestTagHash = execGitCommand('git','log', '-1', '--format=format:%H', tag)
                this.closestTag = tag
            } else if (branchToFindTag != null) {
                if (branchToFindTag.startsWith("bugfix_")) {
                    String version = highestVersionNumber(branchToFindTag)
                    this.closestHighestTagHash = execGitCommand('git', 'rev-list', '-n', '1', version)
                } else if (branchToFindTag.equals("HEAD")) {
                    closestHighestTagHash = currentCommitHash;
                    findGitClosestTag();
                    if (closestTag.contains('-') && !closestTag.contains("-RC") && !closestTag.contains("-M")) {
                        def tag = closestTag.substring(0,closestTag.indexOf('-'))
                        this.closestHighestTagHash = execGitCommand('git', 'rev-list', '-n', '1', tag)
                    }
                } else {
                    logger.info("findClosestTagHash else ")
                    def tag = findGitHighestTag()
                    this.closestHighestTagHash = execGitCommand('git','log', '-1', '--format=format:%H', tag)
                    this.closestTag = tag
                }
            }
        if (closestHighestTagHash == null || closestHighestTagHash.empty) {
            this.closestHighestTagHash = "0"
        }
        logger.debug("Found closestHighestTagHash: " + closestHighestTagHash)

    }

    private String highestVersionNumber(String branch) {
        def extractedVersion = branch.replaceAll("bugfix_", "").replaceAll("_", ".");
        def outputString = execGitCommand('git', 'tag', '-l', extractedVersion + '*', '--sort=v:refname')
        def hashes;
        def version = '0.0.0';
        if (outputString.contains('\n')) {
            hashes = outputString.split('\n');
            for (String item : hashes) {
                if (item.matches("[0-9|.|a-z|R|C|M|-]*")) {
                    if (version.empty || !(item.startsWith(version) && (item.contains("RC") || item.contains("M")))) {
                        version = item
                    }
                }
            }
        } else {
            version = outputString
        }
        return version;
    }

    String findGitHighestTag () {
        def outputString = execGitCommand('git','tag', '-l', '--sort=v:refname')
        if (outputString == null || outputString.empty) {
            return "0.0.0"
        }
        def hashes;
        if (outputString.contains('\n')) {
            hashes = outputString.split('\n');
        } else {
            hashes = [outputString];
        }
        def closestTag = '';
        for (String item : hashes) {
            if (item.matches("[0-9|.|a-z|R|C|M|-]*")) {
                if (closestTag.empty || !(item.startsWith(closestTag) && (item.contains("RC") || item.contains("M")))) {
                    closestTag = item
                }
            }

        }
        return closestTag
    }

    void findGitClosestTag () {
        closestTag = execGitCommand('git', 'describe', '--tags', closestHighestTagHash)
        if (closestTag == null || closestTag.empty) {
            closestTag = "0.0.0";
        }
        if (branch == null) {
            branch = "main"
        }
        if (branch.startsWith('bugfix_') && closestTag.equals('0.0.0')) {
            def extractedVersion = branch.replaceAll("bugfix_", "").replaceAll("_", ".")
            closestTag = extractedVersion + '.0'

        }

        logger.debug("Found ClosestTag: " + closestTag)
    }

    void findVersion() {
        def closestTag = closestTag;
        def gitBranch = branch;
        def versionSplit = /([0-9]+).([0-9]+).([0-9]+).*/;
        def matcher = ( closestTag =~ versionSplit );
        if (closestHighestTagHash.equals(currentCommitHash)) {
            mavenVersion = closestTag;
            def major = matcher[0][1];
            def minor = matcher[0][2];
            def bugfix = matcher[0][3];
            gitPaddedVersionCount = major +
                    String.format("%02d", minor.toLong()) +
                    String.format("%02d", bugfix.toLong())
            snapshot = false;
            if (isProjectDirty()) {
                mavenVersion += '-dirty'
            }
        } else {
            def major = matcher[0][1];
            def minor = matcher[0][2];
            def bugfix = matcher[0][3];
            def branchToFindVersion = branch
            if (!parentBranch.equals(branch)) {
                branchToFindVersion = parentBranch
            }
            if (gitBranch == null) {
                mavenVersion = '0.0.0-SNAPSHOT'
                branch = 'unknown'
                return
            }
            if (gitBranch.equals("main")) {
                if (closestTag.contains("-M")) {
                    minor = 0;
                    bugfix = "0.0-SNAPSHOT";
                } else {
                    major = major.toLong() +1;
                    minor = 0;
                    bugfix = "0-SNAPSHOT";
                }
            } else if (gitBranch.startsWith("bugfix")) {
                if ((closestHighestTagHash.equals('0') && minor.equals('0')) || closestTag.contains("-RC") || closestTag.contains("-M")) {
                    minor = 0;
                    bugfix = bugfix + '-SNAPSHOT'
                } else {
                    minor = (minor.toLong() + 1)
                    bugfix = 0 + "-SNAPSHOT"
                }
            } else {
                def startIdx = 0;
                def endIdx = 11;
                if (gitBranch == null) {
                    gitBranch = "-UNKNOWN";
                } else if (gitBranch.length() >= endIdx) {
                    if (gitBranch.startsWith("SPRINT-")) {
                        startIdx = 7;
                    }
                    gitBranch = "-" + gitBranch.substring(startIdx, endIdx);
                } else if (gitBranch.equals("HEAD")) {
                    gitBranch = "";
                } else {
                    gitBranch = "-" + gitBranch.substring(startIdx, gitBranch.length());
                }
                if (!parentBranch.equals(branch) && parentBranch.startsWith('bugfix')) {
                    bugfix = (bugfix.toLong() + 1) +gitBranch + "-SNAPSHOT"
                } else if (gitBranch.startsWith('-') && closestTag.contains("-M")) {
                    bugfix = "0" + gitBranch + "-SNAPSHOT";
                } else {
                    minor = minor.toLong() + 1;
                    bugfix = "0" + gitBranch + "-SNAPSHOT";
                }
            }
            def bugfixExtracted = '0';
            if (bugfix.indexOf('-')) {
                bugfixExtracted = bugfix.substring(0,bugfix.indexOf('-'));
            } else if (bugfix.isNumber()) {
                bugfixExtracted = bugfix;
            }

            gitPaddedVersionCount = major +
                    String.format("%02d", minor.toLong()) +
                    String.format("%02d", bugfixExtracted.toLong())
            mavenVersion = (major +
                    "." +
                    minor +
                    "." +
                    bugfix);
            if (isProjectDirty()) {
                mavenVersion += '-dirty'
            }
        }
        if (mavenVersion.equals(closestTag) && branch.equals('HEAD')) {
            branch = mavenVersion
        }
        logger.debug("Found mavenVersion: " + mavenVersion)
        logger.debug("Found gitPaddedVersionCount: " + gitPaddedVersionCount)
    }

    void findGitDescribeVersion() {
        if (currentCommitHash.equals(closestHighestTagHash)) {
            gitDescribe = closestTag;
        } else {
            gitDescribe = getMavenVersion()+ '-'+ currentShortCommitHash;
        }
        logger.debug("found gitDescribe: " + gitDescribe)
    }

    void findGitAppDescribeVersion() {
        if (currentCommitHash.equals(closestHighestTagHash)) {
            gitAppDescribe = closestTag;
        } else {
            gitAppDescribe = gitDescribe.replaceAll('-SNAPSHOT','');
        }
        appVersion = mavenVersion.replaceAll('-SNAPSHOT','');
        logger.debug("found gitAppDescribe: " + gitAppDescribe)
    }

    @Internal
    boolean isProjectDirty() {
        def resultValue = execGitCommand('git', 'status', '--porcelain')
        if (resultValue != null && resultValue.length() > 2) {
            return resultValue.split("\n").size() > 0
        } else {
            return false
        }
    }

    int compareVersions(String v1, String v2) {

        if (v1.length() > 0  && v2.length() == 0) return -1;
        if (v1.length() == 0 && v2.length() == 0) return 0;
        if (v1.length() == 0 && v2.length() < 0) return 1;

        int pos1 = v1.indexOf('.');
        int pos2 = v2.indexOf('.');

        Integer num1 = (pos1 > 0 ? Integer.valueOf(v1.substring(0, pos1)) : 0);
        Integer num2 = (pos2 > 0 ? Integer.valueOf(v2.substring(0, pos2)) : 0);

        if (num1 != num2) return num1.compareTo(num2);

        String tail1 = (pos1 > 0 ? v1.substring(pos1 + 1, v1.length()) : "");
        String tail2 = (pos2 > 0 ? v2.substring(pos2 + 1, v2.length()) : "");

        return compareVersions(tail1, tail2);
    }
}
