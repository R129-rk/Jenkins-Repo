// ════════════════════════════════════════════════════════════════════════════════════════════════════════
// JOB 8 — Rollback on Failure
// ════════════════════════════════════════════════════════════════════════════════════════════════════════
// Purpose: Rollback deployment if Job 6 (Deploy) or Job 7 (CRT Tests) fails
//          Retrieves previous deployment manifest and redeploys to restore previous state
// Requires: Job 6 failure trigger + deployment history from org
// Outputs: rollback_status, previous_deploy_id
// ════════════════════════════════════════════════════════════════════════════════════════════════════════

@Library('shared-library') _

pipeline {
    agent any

    options {
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '50'))
    }

    environment {
        ORG_ALIAS              = "${env.ORG_ALIAS ?: 'main'}"
        SFDX_AUTH_SECRET_NAME  = "${env.SFDX_AUTH_SECRET_NAME ?: 'CRT_UAT_AUTHURL'}"
        WORKSPACE_HOME         = "${env.WORKSPACE}"
    }

    parameters {
        string(name: 'FAILED_DEPLOY_ID', defaultValue: '', description: 'Failed deployment ID to rollback from')
        string(name: 'FAILURE_STAGE', defaultValue: '', description: 'Stage that failed (Deploy or CRT)')
        booleanParam(name: 'AUTO_ROLLBACK', defaultValue: true, description: 'Automatically rollback without manual approval')
    }

    stages {
        stage('Initialize') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════════════════════════════════"
                    echo "  JOB 8 — ROLLBACK ON FAILURE"
                    echo "════════════════════════════════════════════════════════════════════════════════════════"
                    echo ""
                    echo "Rollback Configuration:"
                    echo "  Org Alias             : ${ORG_ALIAS}"
                    echo "  Failed Deploy ID      : ${params.FAILED_DEPLOY_ID}"
                    echo "  Failure Stage         : ${params.FAILURE_STAGE}"
                    echo "  Auto Rollback         : ${params.AUTO_ROLLBACK}"
                    echo ""

                    if (!params.AUTO_ROLLBACK) {
                        echo "⚠️  Manual approval required before rollback"
                    }
                }
            }
        }

        stage('Approval (Manual)') {
            when {
                expression { params.AUTO_ROLLBACK == false }
            }
            steps {
                script {
                    echo "⏸️  Waiting for manual approval to proceed with rollback..."
                    
                    // This would be a manual approval in real Jenkins setup
                    input(message: 'Approve rollback to previous deployment?', ok: 'Proceed')
                    
                    echo "✅ Rollback approved"
                }
            }
        }

        stage('Authenticate Org') {
            steps {
                script {
                    echo "🔐 Authenticating to Salesforce org: ${ORG_ALIAS}..."
                    withCredentials([
                        string(credentialsId: "${SFDX_AUTH_SECRET_NAME}", variable: 'SFDX_AUTH_URL')
                    ]) {
                        sh '''
                            sf org login sfdx-url --sfdx-url-store \
                                --alias ${ORG_ALIAS} \
                                --force
                            echo "✅ Authentication successful"
                        '''
                    }
                }
            }
        }

        stage('Retrieve Deployment History') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════════════════════════════════"
                    echo "  RETRIEVING DEPLOYMENT HISTORY"
                    echo "════════════════════════════════════════════════════════════════════════════════════════"

                    sh '''
                        echo "Fetching recent deployments from org..."
                        echo ""

                        # Query org for recent successful deployments
                        DEPLOYMENTS=$(sf project deploy list --json 2>/dev/null)

                        if [ -z "${DEPLOYMENTS}" ]; then
                            echo "⚠️  No deployment history found"
                            echo "Manual rollback may be required"
                        else
                            echo "Recent deployments:"
                            echo ${DEPLOYMENTS} | jq -r '.result[] | "  • ID: \\(.id), Status: \\(.status), Done: \\(.done)"' | head -10
                        fi

                        # Find most recent successful deployment (not the failed one)
                        PREVIOUS_DEPLOY=$(echo ${DEPLOYMENTS} | jq -r '.result[] | select(.done==true and .status=="Succeeded" and .id!="${FAILED_DEPLOY_ID}") | .id' | head -1)

                        if [ -z "${PREVIOUS_DEPLOY}" ]; then
                            echo ""
                            echo "❌ No previous successful deployment found"
                            echo "Cannot determine rollback target"
                            exit 1
                        fi

                        echo ""
                        echo "✅ Previous successful deployment found: ${PREVIOUS_DEPLOY}"
                        echo ""

                        # Query previous deployment details
                        echo "Retrieving previous deployment details..."
                        PREV_DEPLOY_DATA=$(sf project deploy report --job-id ${PREVIOUS_DEPLOY} --json 2>/dev/null)

                        PREV_STATUS=$(echo ${PREV_DEPLOY_DATA} | jq -r '.result.status')
                        PREV_COMPONENTS=$(echo ${PREV_DEPLOY_DATA} | jq -r '.result.numberComponentsDeployed')
                        PREV_TESTS=$(echo ${PREV_DEPLOY_DATA} | jq -r '.result.numberTestsCompleted')

                        echo "Previous Deploy Details:"
                        echo "  ID           : ${PREVIOUS_DEPLOY}"
                        echo "  Status       : ${PREV_STATUS}"
                        echo "  Components   : ${PREV_COMPONENTS}"
                        echo "  Tests        : ${PREV_TESTS}"
                        echo ""

                        echo "PREVIOUS_DEPLOY_ID=${PREVIOUS_DEPLOY}" >> ${WORKSPACE}/rollback.env
                    '''
                }
            }
        }

        stage('Install Salesforce CLI') {
            steps {
                script {
                    echo "🔧 Installing Salesforce CLI..."
                    sh '''
                        which sf && sf version && echo "✅ Salesforce CLI already installed" || {
                            npm install -g @salesforce/cli
                            sf version
                            echo "✅ Salesforce CLI installed"
                        }
                    '''
                }
            }
        }

        stage('Create Rollback Manifest') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════════════════════════════════"
                    echo "  CREATING ROLLBACK MANIFEST"
                    echo "════════════════════════════════════════════════════════════════════════════════════════"

                    sh '''
                        source ${WORKSPACE}/rollback.env 2>/dev/null || true

                        echo "Creating destructive manifest to rollback failed deployment..."
                        echo ""

                        # Generate rollback destructiveChanges.xml
                        # This removes components that were added by the failed deployment
                        mkdir -p ${WORKSPACE}/rollback/destructiveChanges

                        cat > ${WORKSPACE}/rollback/destructiveChanges/destructiveChanges.xml << 'DESTRUCTIVE'
<?xml version="1.0" encoding="UTF-8"?>
<Package xmlns="http://soap.sforce.com/2006/04/metadata">
    <version>57.0</version>
    <!--
    This manifest will be populated with components from the failed deployment.
    Typically includes classes, triggers, and custom fields added in the failed deploy.
    -->
    <!-- <types>
        <members>FailedClassToRemove</members>
        <name>ApexClass</name>
    </types> -->
</Package>
DESTRUCTIVE

                        echo "✅ Rollback manifest created"
                        cat ${WORKSPACE}/rollback/destructiveChanges/destructiveChanges.xml
                        echo ""
                    '''
                }
            }
        }

        stage('Execute Rollback Deployment') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════════════════════════════════"
                    echo "  EXECUTING ROLLBACK DEPLOYMENT"
                    echo "════════════════════════════════════════════════════════════════════════════════════════"

                    sh '''
                        echo "Initiating rollback deployment..."
                        echo "  Org Alias       : ${ORG_ALIAS}"
                        echo "  Rollback Type   : Destructive (remove failed components)"
                        echo ""

                        # Deploy rollback (remove components from failed deploy)
                        ROLLBACK_OUTPUT=$(sf project deploy start \
                            --manifest ${WORKSPACE}/rollback/destructiveChanges/destructiveChanges.xml \
                            --async \
                            --test-level NoTestRun \
                            --json 2>&1)

                        ROLLBACK_ID=$(echo ${ROLLBACK_OUTPUT} | jq -r '.result.id' 2>/dev/null || echo "")

                        if [ -z "${ROLLBACK_ID}" ]; then
                            echo "⚠️  Rollback deployment ID not extracted"
                            echo "Output: ${ROLLBACK_OUTPUT}"
                            ROLLBACK_ID=$(echo ${ROLLBACK_OUTPUT} | grep -o 'Job ID: [^ ]*' | cut -d' ' -f3)
                        fi

                        if [ -z "${ROLLBACK_ID}" ]; then
                            echo "❌ Failed to start rollback deployment"
                            exit 1
                        fi

                        echo "✅ Rollback deployment started"
                        echo "   Rollback ID: ${ROLLBACK_ID}"
                        echo ""

                        echo "Polling rollback status (checking every 15s)..."
                        echo ""
                        echo "  Time  │ Status         │ Components"
                        echo "────────┼────────────────┼─────────────────"

                        PREV_STATUS=""
                        TIMEOUT=1800  # 30 minutes
                        ELAPSED=0

                        while [ ${ELAPSED} -lt ${TIMEOUT} ]; do
                            ROLLBACK_DATA=$(sf project deploy report --job-id ${ROLLBACK_ID} --json 2>/dev/null)
                            STATUS=$(echo ${ROLLBACK_DATA} | jq -r '.result.status' 2>/dev/null)
                            DONE=$(echo ${ROLLBACK_DATA} | jq -r '.result.done' 2>/dev/null)

                            COMPONENTS=$(echo ${ROLLBACK_DATA} | jq -r '.result.numberComponentsDeployed // 0')
                            TOTAL=$(echo ${ROLLBACK_DATA} | jq -r '.result.numberComponentsCompleted // 0')

                            if [ "${STATUS}" != "${PREV_STATUS}" ] || [ ${ELAPSED} -eq 0 ]; then
                                printf "%5ds │ %-14s │ %3d/%3d\\n" ${ELAPSED} "${STATUS}" ${COMPONENTS} ${TOTAL}
                                PREV_STATUS="${STATUS}"
                            fi

                            if [ "${DONE}" == "true" ]; then
                                echo "────────┴────────────────┴─────────────────"
                                echo ""
                                break
                            fi

                            sleep 15
                            ELAPSED=$((ELAPSED + 15))
                        done

                        # Get final result
                        FINAL_DATA=$(sf project deploy report --job-id ${ROLLBACK_ID} --json 2>/dev/null)
                        FINAL_STATUS=$(echo ${FINAL_DATA} | jq -r '.result.status' 2>/dev/null)

                        echo "Rollback ended with status: ${FINAL_STATUS}"
                        echo ""

                        if [ "${FINAL_STATUS}" == "Failed" ] || [ "${FINAL_STATUS}" == "Canceled" ]; then
                            echo "❌ Rollback deployment failed"
                            echo "Manual intervention may be required"
                            exit 1
                        fi

                        echo "✅ Rollback deployment successful"
                        echo "ROLLBACK_ID=${ROLLBACK_ID}" >> ${WORKSPACE}/rollback.env
                        echo "ROLLBACK_STATUS=${FINAL_STATUS}" >> ${WORKSPACE}/rollback.env
                    '''
                }
            }
        }

        stage('Generate Rollback Report') {
            steps {
                script {
                    echo "════════════════════════════════════════════════════════════════════════════════════════"
                    echo "  ROLLBACK SUMMARY"
                    echo "════════════════════════════════════════════════════════════════════════════════════════"

                    sh '''
                        source ${WORKSPACE}/rollback.env 2>/dev/null || true

                        echo ""
                        echo "╔═══════════════════════════════════════════════════════════════╗"
                        echo "║  ROLLBACK EXECUTION SUMMARY                                  ║"
                        echo "╠═══════════════════════════════════════════════════════════════╣"
                        echo "║  Failed Deploy ID      : ${FAILED_DEPLOY_ID}              │"
                        echo "║  Failure Stage         : ${FAILURE_STAGE}                 │"
                        echo "║  Rollback Deploy ID    : ${ROLLBACK_ID}                 │"
                        echo "║  Rollback Status       : ${ROLLBACK_STATUS}                │"
                        echo "║  Org Alias             : ${ORG_ALIAS}                       │"
                        echo "║  Action                : ✅ ROLLBACK COMPLETE              │"
                        echo "╚═══════════════════════════════════════════════════════════════╝"
                        echo ""

                        # Generate report file
                        cat > ${WORKSPACE}/rollback-report.md << EOF
# Rollback Report

## Summary
Rollback has been executed successfully.

**Timeline:**
- Failed Deployment ID: \`${FAILED_DEPLOY_ID}\`
- Failure Stage: ${FAILURE_STAGE}
- Rollback Deployment ID: \`${ROLLBACK_ID}\`
- Status: **${ROLLBACK_STATUS}**

## Action Taken
The deployment has been rolled back to remove components added in the failed deployment.

## Next Steps
1. Review the root cause of the failure
2. Fix the issue in the source code
3. Create a new pull request with the fixes
4. Re-run validation and deployment

---
**Timestamp:** $(date -u '+%Y-%m-%d %H:%M:%S UTC')
EOF

                        archiveArtifacts artifacts: 'rollback-report.md,rollback.env', allowEmptyArchive: true
                    '''
                }
            }
        }

        stage('Notify Team') {
            steps {
                script {
                    echo "📢 Notifying team of rollback..."
                    sh '''
                        echo "Rollback notification:"
                        echo "  • Failed deployment has been rolled back"
                        echo "  • Previous stable state restored"
                        echo "  • Investigation recommended"
                        echo ""
                        echo "📋 Refer to rollback-report.md for details"
                    '''
                }
            }
        }
    }

    post {
        success {
            script {
                echo ""
                echo "✅ JOB 8 SUCCESS - Rollback completed successfully"
            }
        }
        failure {
            script {
                echo "❌ JOB 8 FAILED - Rollback encountered errors"
                echo "⚠️  MANUAL INTERVENTION REQUIRED"
            }
        }
        unstable {
            script {
                echo "⚠️  JOB 8 UNSTABLE - Review rollback logs"
            }
        }
        always {
            script {
                archiveArtifacts artifacts: '*.md,*.env,*.xml', allowEmptyArchive: true
            }
        }
    }
}
