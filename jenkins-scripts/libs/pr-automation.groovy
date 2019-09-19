/*
 * Copyright 2019 ForgeRock AS. All Rights Reserved
 *
 * Use of this code requires a commercial software license with ForgeRock AS.
 * or with one of its affiliates. All use shall be exclusively subject
 * to such license between the licensee and ForgeRock AS.
 */

import com.forgerock.pipeline.GlobalConfig
import com.forgerock.pipeline.reporting.PipelineRun
import com.forgerock.pipeline.stage.Status

/**
 * Merge the pull request if it is a product increment opened automatically by Rockbot.
 *
 * Typically these types of pull request are raised after a successful product postcommit build.
 * If the pull request cannot be fast-forward merged, rebase the PR - this causes a new build to run.
 *
 * @param currentBuildCommit Git commit used in this build.
 */
void mergeIfAutomatedProductVersionUpdate(PipelineRun pipelineRun, String currentBuildCommit) {
    String project = scmUtils.getProjectName()
    String repo = scmUtils.getRepoName()
    String creds = credsId()

    if (isAutomatedPullRequest()) {
        updatePrData()

        // Possible time-of-check to time-of-use race condition
        String latestPrVersion = PR_DATA.version.toString()
        String latestCommitOnPrBranch = PR_DATA.fromRef.latestCommit

        /*
         * The commit used in this build could be out of date with the PR if someone pushed while the build was running.
         * If PR is up to date and commit on target branch is the parent of this commit, merge the PR, else rebase.
         */
        if (currentBuildCommit == latestCommitOnPrBranch &&
                canDoFastForwardMerge(currentBuildCommit, PR_DATA.toRef.latestCommit)) {
            pipelineRun.pushStageOutcome(
                    "promote-to-forgeops-${env.CHANGE_TARGET}",
                    stageDisplayName: "Promote to ForgeOps") {
                bitbucketUtils.mergePullRequest(creds, project, repo, env.CHANGE_ID, latestPrVersion)
                return Status.SUCCESS.asOutcome()
            }
        } else {
            bitbucketUtils.rebasePullRequest(creds, project, repo, env.CHANGE_ID, latestPrVersion)
        }
    }
}

/** Map representation of the Pull Request. */
private Map getCurrentPrData() {
    return bitbucketUtils.getPullRequestData(
            credsId(), scmUtils.getProjectName(), scmUtils.getRepoName(), env.CHANGE_ID
    )
}

/** Update the internal pull request representation with up-to-date information. */
private void updatePrData() {
    PR_DATA = getCurrentPrData()
}

/**
 * Map representation of the pull request. Set once, when the module is loaded; may contain outdated PR information.
 * Update this value using updatePrData() when it's necessary to have up-to-date PR information.
 */
PR_DATA = getCurrentPrData()

/** Determine if this is an automated pull request. */
boolean isAutomatedPullRequest() {
    return PR_DATA.author.user.slug == GlobalConfig.FORGEOPS_AUTOMATED_PR_AUTHOR &&
            PR_DATA.title.startsWith(GlobalConfig.FORGEOPS_AUTOMATED_PR_TITLE_PREFIX)
}

/**
 *  Get the product commit hash from the PR title, if the PR is a product increment opened automatically by Rockbot.
 *  e.g. AUTOMATED PRODUCT INCREMENT: AM 7.0.0-830a7a99713c5a8267d0fc18386a929a491d6ef1
 */
String getProductCommitHash() {
    String commitHash = PR_DATA.title.split('-')[-1]

    if (!commitHash.matches('[0-9a-fA-F]+')) {
        error "Product commit hash cannot be extracted from PR title '${PR_DATA.title}'\n" +
                'Only call getProductCommitHash() on automated Pull Requests created by ' +
                GlobalConfig.FORGEOPS_AUTOMATED_PR_AUTHOR
    }

    return commitHash
}

/*
 * Look at git history to determine whether a fast-forward merge is possible.
 */
private boolean canDoFastForwardMerge(String currentBuildCommit, String latestCommitOnTargetBranch) {
    String project = scmUtils.getProjectName()
    String repo = scmUtils.getRepoName()

    String pullRequestParentCommit = httpUtils.sendBasicAuthJsonRequest(
            'GET',
            "${GlobalConfig.bitbucketUrl}/rest/api/1.0/projects/${project}/repos/${repo}/commits/${currentBuildCommit}",
            credsId()
    ).parents[0].id

    return pullRequestParentCommit == latestCommitOnTargetBranch
}

private String credsId() {
    return 'rockbot-backstage-credentials'
}

return this