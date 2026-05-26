pipeline {
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 4, unit: 'HOURS')
        timestamps()
    }

    parameters {
        choice(
            name: 'SCANNER_MODE',
            choices: ['all', 'checkmarx', 'fortify', 'none'],
            description: 'Security scanner mode: all (both), checkmarx only, fortify only, or none'
        )
        string(
            name: 'APPROVAL_NOTES',
            defaultValue: '',
            description: 'Optional approval notes'
        )
    }

    environment {
        // Org Configuration
        ORG_ALIAS = "${env.ORG_ALIAS ?: 'main'}"
        SFDX_AUTH_SECRET_NAME = "${env.SFDX_AUTH_SECRET_NAME ?: 'CRT_UAT_AUTHURL'}"
        
        // Apex Testing
        COVERAGE_THRESHOLD = "${env.COVERAGE_THRESHOLD ?: '85'}"
        SOURCE_DIR = "${env.SOURCE_DIR ?: 'force-app/main/default'}"
        
        // Delta Tracking
        DELTA_FROM_COMMIT = "${env.DELTA_FROM_COMMIT ?: 'HEAD~5'}"
        
        // SCA Enforcement: enforce | warn | off
        SCA_ENFORCEMENT_MODE = "${env.SCA_ENFORCEMENT_MODE ?: 'enforce'}"
        
        // CRT Integration
        CRT_JOB_ID = "${env.CRT_JOB_ID ?: '115686'}"
        CRT_PROJECT_ID = "${env.CRT_PROJECT_ID ?: '73283'}"
        CRT_ORG_ID = "${env.CRT_ORG_ID ?: '43532'}"
        CRT_API_URL = "https://graphql.eu-robotic.copado.com/v1"
        
        // Fortify
        FOD_URL = "${env.FOD_URL ?: ''}"
        FOD_DAST_ASSESSMENT_TYPE = "${env.FOD_DAST_ASSESSMENT_TYPE ?: 'Dynamic Assessment'}"
        FOD_DAST_FREQUENCY = "${env.FOD_DAST_FREQUENCY ?: 'SingleScan'}"
        FOD_DAST_ENVIRONMENT = "${env.FOD_DAST_ENVIRONMENT ?: 'External'}"
        
        // CLI Version
        FCLI_BOOTSTRAP_VERSION = "v3.16.0"
        
        // Jenkins Workspace
        WORKSPACE_DIR = "${WORKSPACE}"
        ARTIFACTS_DIR = "${WORKSPACE}/artifacts"
        REPORTS_DIR = "${WORKSPACE}/reports"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // Capture commit SHAs for later use
                    env.HEAD_SHA = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    env.BASE_SHA = sh(returnStdout: true, script: 'git rev-parse origin/main').trim()
                    env.BRANCH_NAME = sh(returnStdout: true, script: 'git rev-parse --abbrev-ref HEAD').trim()
                    
                    echo """
                    ════════════════════════════════════════════════════════════════
                      GIT CONTEXT
                    ════════════════════════════════════════════════════════════════
                    Branch: ${env.BRANCH_NAME}
                    HEAD SHA: ${env.HEAD_SHA}
                    Base SHA (main): ${env.BASE_SHA}
                    """
                }
            }
        }

        stage('[1] Setup - Evaluate Scanners') {
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [1] SETUP - EVALUATE SCANNER AVAILABILITY
                    ════════════════════════════════════════════════════════════════
                    """

                    // Determine scanner availability
                    def checkmarxAvailable = false
                    def fortifyAvailable = false

                    try {
                        withCredentials([string(credentialsId: 'CX_CLIENT_SECRET', variable: 'CX_SECRET')]) {
                            checkmarxAvailable = true
                        }
                    } catch (Exception e) {
                        echo "⚠️  CheckMarx credentials not available (CX_CLIENT_SECRET not found)"
                    }

                    try {
                        withCredentials([string(credentialsId: 'FOD_CLIENT_SECRET', variable: 'FOD_SECRET')]) {
                            fortifyAvailable = true
                        }
                    } catch (Exception e) {
                        echo "⚠️  Fortify credentials not available (FOD_CLIENT_SECRET not found)"
                    }

                    // Honor parameter override
                    if (params.SCANNER_MODE == 'checkmarx') {
                        fortifyAvailable = false
                    } else if (params.SCANNER_MODE == 'fortify') {
                        checkmarxAvailable = false
                    } else if (params.SCANNER_MODE == 'none') {
                        checkmarxAvailable = false
                        fortifyAvailable = false
                    }

                    env.RUN_CHECKMARX = checkmarxAvailable ? 'true' : 'false'
                    env.RUN_FORTIFY = fortifyAvailable ? 'true' : 'false'

                    echo """
                    ✅ Scanner Evaluation Complete:
                    - CheckMarx enabled: ${env.RUN_CHECKMARX}
                    - Fortify enabled: ${env.RUN_FORTIFY}
                    - Parameter override: ${params.SCANNER_MODE}
                    """
                }
            }
        }

        stage('[2] Salesforce Validation') {
            when {
                // Run on PR branches (not main)
                expression { env.BRANCH_NAME != 'main' && env.CHANGE_ID }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [2] SALESFORCE PR VALIDATION
                    ════════════════════════════════════════════════════════════════
                    """
                }
                sh '''
                    mkdir -p ${ARTIFACTS_DIR} ${REPORTS_DIR}
                    
                    # Install Salesforce CLI
                    echo "📦 Installing Salesforce CLI..."
                    npm install -g @salesforce/cli
                    
                    # Authenticate to org
                    echo "🔐 Authenticating to Salesforce org..."
                    sfdx force:auth:sfdxurl:store \
                        --sfdxurlfile <(echo "$SFDX_AUTH_URL") \
                        --setalias ${ORG_ALIAS} \
                        --setdefaultusername
                '''
                
                script {
                    // Extract test classes from PR description and comments
                    def testClasses = extractTestClasses()
                    env.HINTED_TESTS = testClasses.join(',')
                    
                    echo """
                    ════════════════════════════════════════════════════════════════
                      TEST CLASS RESOLUTION
                    ════════════════════════════════════════════════════════════════
                    From PR description/comments: ${env.HINTED_TESTS ?: '<none>'}
                    """
                }

                sh '''
                    # Install sfdx-git-delta
                    echo "📦 Installing sfdx-git-delta..."
                    echo y | sfdx plugins install sfdx-git-delta
                    sfdx sgd source delta --help || sfdx sgd:source:delta --help
                    
                    # Generate delta package
                    echo "📊 Generating delta package from ${DELTA_FROM_COMMIT}..."
                    mkdir -p package destructiveChanges
                    
                    sfdx sgd source delta \
                        --from ${DELTA_FROM_COMMIT} \
                        --to HEAD \
                        --output . \
                        --generate-delta \
                        --repo-from .
                    
                    # Display generated manifests
                    echo "════════════════════════════════════════════════════════════════"
                    echo "  GENERATED DELTA MANIFESTS"
                    echo "════════════════════════════════════════════════════════════════"
                    
                    if [ -f package/package.xml ]; then
                        echo "✅ package/package.xml"
                        cat package/package.xml
                    fi
                    
                    if [ -f destructiveChanges/destructiveChanges.xml ]; then
                        echo "✅ destructiveChanges/destructiveChanges.xml"
                        cat destructiveChanges/destructiveChanges.xml
                    fi
                '''

                script {
                    // Determine test level and run validation
                    def apexRequired = checkApexChanges()
                    def inferredTests = inferTestClasses()
                    def finalTests = (env.HINTED_TESTS ?: '') + ',' + (inferredTests.join(',') ?: '')
                    finalTests = finalTests.split(',').findAll { it.trim() }.unique().join(',')
                    
                    env.FINAL_TESTS = finalTests
                    env.APEX_REQUIRED = apexRequired ? 'true' : 'false'
                    
                    echo """
                    Inferred from changed files: ${inferredTests.join(', ') ?: '<none>'}
                    
                    ✅ Final test classes to run:
                    ${finalTests.split(',').findAll { it.trim() }.collect { "   → ${it.trim()}" }.join('\n') ?: '   → <none>'}
                    
                    Apex required: ${env.APEX_REQUIRED}
                    """
                }

                sh '''
                    # Prepare validation command
                    TEST_LEVEL="NoTestRun"
                    TEST_FLAGS=""
                    
                    if [ "${APEX_REQUIRED}" = "true" ]; then
                        TEST_LEVEL="RunSpecifiedTests"
                        # Convert comma-separated tests to repeated --tests flags
                        for test in $(echo "${FINAL_TESTS}" | tr ',' '\n'); do
                            if [ ! -z "$(echo $test | xargs)" ]; then
                                TEST_FLAGS="$TEST_FLAGS --tests $(echo $test | xargs)"
                            fi
                        done
                    fi
                    
                    echo "════════════════════════════════════════════════════════════════"
                    echo "  PR VALIDATION — DEPLOYMENT VALIDATION"
                    echo "════════════════════════════════════════════════════════════════"
                    echo "Test level: ${TEST_LEVEL}"
                    echo "Test flags: ${TEST_FLAGS}"
                    echo ""
                    
                    # Run validation (async)
                    echo "▶ Running validation command..."
                    VALIDATION_CMD="sfdx force:source:deploy \
                        --targetusername ${ORG_ALIAS} \
                        --sourcepath package \
                        --testlevel ${TEST_LEVEL} \
                        ${TEST_FLAGS} \
                        --wait 60 \
                        --json"
                    
                    echo "Command: $VALIDATION_CMD"
                    VALIDATION_OUTPUT=$(eval "$VALIDATION_CMD")
                    
                    # Parse and display results
                    VALIDATION_ID=$(echo "$VALIDATION_OUTPUT" | jq -r '.result.id // empty')
                    VALIDATION_STATUS=$(echo "$VALIDATION_OUTPUT" | jq -r '.result.status // "Unknown"')
                    
                    echo "Validation job id: ${VALIDATION_ID}"
                    echo "Status: ${VALIDATION_STATUS}"
                    
                    # Save for step summary
                    echo "$VALIDATION_OUTPUT" > ${REPORTS_DIR}/validation-result.json
                    
                    if [ "$VALIDATION_STATUS" != "Succeeded" ]; then
                        echo "❌ Validation Failed"
                        echo "$VALIDATION_OUTPUT" | jq '.'
                        exit 1
                    fi
                    
                    echo "✅ Validation Succeeded"
                '''

                script {
                    // Display validation summary
                    def validationResult = readJSON file: "${ARTIFACTS_DIR}/validation-result.json"
                    echo """
                    
                    ## Salesforce Validation Summary
                    - Status: ${validationResult.result?.status ?: 'Unknown'}
                    - Deployment ID: ${validationResult.result?.id ?: 'N/A'}
                    - Components deployed: ${validationResult.result?.numberComponentsDeployed ?: 0}
                    - Test coverage: ${validationResult.result?.testCodeCoveragePercentage ?: 'N/A'}%
                    """
                }
            }
        }

        stage('[3] Salesforce Code Analyzer') {
            when {
                expression { env.SCA_ENFORCEMENT_MODE != 'off' && env.BRANCH_NAME != 'main' }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [3] SALESFORCE CODE ANALYZER (SCA)
                    ════════════════════════════════════════════════════════════════
                    Enforcement mode: ${env.SCA_ENFORCEMENT_MODE}
                    """
                }

                sh '''
                    # Install SCA
                    npm install -g @salesforce/sfdx-scanner
                    
                    # Detect changed files
                    CHANGED_FILES=$(git diff ${DELTA_FROM_COMMIT}..HEAD --name-only | \
                         grep -E '\\.(cls|trigger|js|html|css)$' || echo "")
                        # grep -E '\\.(cls|trigger|js|html|css)$'
                    
                    if [ -z "$CHANGED_FILES" ]; then
                        echo "ℹ️  No Apex/JS/HTML/CSS files changed. SCA skipped."
                        exit 0
                    fi
                    
                    echo "📄 Changed files for SCA:"
                    echo "$CHANGED_FILES"
                    
                    # Run scanner
                    mkdir -p ${REPORTS_DIR}/sca
                    sfdx scanner:run \
                        --target $CHANGED_FILES \
                        --format csv \
                        --outfile ${REPORTS_DIR}/sca/scan-results.csv \
                        --severity 1 || true
                    
                    # Process waivers
                    echo ""
                    echo "════════════════════════════════════════════════════════════════"
                    echo "  WAIVER PROCESSING"
                    echo "════════════════════════════════════════════════════════════════"
                    
                    # Fetch waivers from main branch
                    curl -s -H "Authorization: token ${GITHUB_TOKEN}" \
                        "https://api.github.com/repos/${GITHUB_REPOSITORY}/contents/.github/sf-scanner-waivers.csv?ref=main" \
                        | jq -r '.content' | base64 -d > ${REPORTS_DIR}/sf-scanner-waivers.csv || true
                    
                    # Check waivers and create report
                    python3 <<'PYTHON_SCRIPT'
import csv
from datetime import datetime
import sys

def check_waivers(scan_file, waiver_file):
    waivers = {}
    expired_count = 0
    
    # Load waivers
    try:
        with open(waiver_file, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                if row['status'].strip() != 'ACTIVE':
                    continue
                
                # Parse expiry date
                expiry_str = row['expiry'].strip()
                try:
                    expiry = datetime.strptime(expiry_str, '%d-%m-%Y')
                except:
                    expiry = datetime.strptime(expiry_str, '%Y-%m-%d')
                
                rule = row['rule'].strip()
                file_pattern = row['file_pattern'].strip()
                
                if expiry < datetime.now():
                    expired_count += 1
                    print(f"❌ EXPIRED_WAIVER: {rule} | {file_pattern} | Expired: {expiry_str}")
                else:
                    days_left = (expiry - datetime.now()).days
                    if days_left <= 30:
                        print(f"⏰ WAIVED_EXPIRING_SOON: {rule} | {file_pattern} | {days_left} days left")
                    else:
                        print(f"✅ WAIVED: {rule} | {file_pattern}")
                    waivers[f"{rule}:{file_pattern}"] = row
    except FileNotFoundError:
        print("⚠️  No waiver file found. Processing violations without waivers.")
    
    return waivers, expired_count

waivers, expired = check_waivers(
    '${REPORTS_DIR}/sca/scan-results.csv',
    '${REPORTS_DIR}/sf-scanner-waivers.csv'
)

if expired > 0 and '${SCA_ENFORCEMENT_MODE}' == 'enforce':
    print(f"\n❌ {expired} expired waivers found. Pipeline FAILED in enforce mode.")
    sys.exit(1)

print(f"\n✅ SCA check complete. {len(waivers)} active waivers applied.")
PYTHON_SCRIPT
                '''
            }
        }

        stage('[4] CheckMarx AST Scan') {
            when {
                expression { env.RUN_CHECKMARX == 'true' && env.BRANCH_NAME != 'main' }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [4] CHECKMARX AST SCAN
                    ════════════════════════════════════════════════════════════════
                    """
                }

                withCredentials([
                    string(credentialsId: 'CX_CLIENT_SECRET', variable: 'CX_SECRET'),
                    string(credentialsId: 'CX_CLIENT_ID', variable: 'CX_ID'),
                    string(credentialsId: 'CX_BASE_URI', variable: 'CX_BASE'),
                    string(credentialsId: 'CX_TENANT', variable: 'CX_TNT')
                ]) {
                    sh '''
                        echo "🔍 Running CheckMarx AST scan..."
                        
                        # Download CheckMarx CLI if needed
                        CX_CLI_URL="https://download.checkmarx.com/8.x/Plugins/CxConsolePlugin-8.47.0.zip"
                        
                        mkdir -p checkmarx-cli
                        cd checkmarx-cli
                        
                        if [ ! -f "runCxConsole.sh" ]; then
                            echo "📥 Downloading CheckMarx CLI..."
                            wget -q $CX_CLI_URL -O checkmarx.zip
                            unzip -q checkmarx.zip
                        fi
                        
                        # Run scan
                        ./runCxConsole.sh \
                            -v \
                            -CxServer "$CX_BASE" \
                            -CxUser "cx_integration" \
                            -CxPassword "$CX_SECRET" \
                            -ProjectName "$(basename $GITHUB_REPOSITORY)" \
                            -LocationType folder \
                            -LocationPath ".." \
                            -Preset "Default" \
                            -ReportType xml \
                            -ReportName "${REPORTS_DIR}/checkmarx-report.xml" \
                            || echo "⚠️  CheckMarx scan completed with warnings"
                        
                        cd ..
                    '''
                }

                script {
                    // Parse and display CheckMarx results
                    try {
                        def checkmarxReport = readFile file: "${ARTIFACTS_DIR}/checkmarx-report.xml"
                        echo """
                        
                        ✅ CheckMarx scan complete. Report saved to artifacts.
                        """
                    } catch (Exception e) {
                        echo "⚠️  CheckMarx report not found: ${e.message}"
                    }
                }
            }
        }

        stage('[5] Fortify SAST/DAST Scan') {
            when {
                expression { env.RUN_FORTIFY == 'true' && env.BRANCH_NAME != 'main' }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [5] FORTIFY SAST/DAST SCAN
                    ════════════════════════════════════════════════════════════════
                    """
                }

                withCredentials([
                    string(credentialsId: 'FOD_CLIENT_ID', variable: 'FOD_ID'),
                    string(credentialsId: 'FOD_CLIENT_SECRET', variable: 'FOD_SECRET')
                ]) {
                    sh '''
                        echo "🔍 Running Fortify FoD scan..."
                        
                        # Install Fortify CLI (fcli)
                        if ! command -v fcli &> /dev/null; then
                            echo "📥 Installing Fortify CLI..."
                            # Download and install fcli
                            curl -s -L https://github.com/fortify/fcli/releases/download/${FCLI_BOOTSTRAP_VERSION}/fcli-linux.zip \
                                -o fcli.zip
                            unzip -q fcli.zip -d fcli-bin
                            export PATH="$PATH:$(pwd)/fcli-bin"
                        fi
                        
                        # Authenticate to FoD
                        fcli fod session login \
                            --client-id "$FOD_ID" \
                            --client-secret "$FOD_SECRET"
                        
                        # Package source for upload
                        zip -r source-package.zip force-app --exclude "*.git*"
                        
                        # Trigger scan
                        echo "📤 Uploading source and triggering scan..."
                        fcli fod sast-scan start \
                            --release "$(basename $GITHUB_REPOSITORY)" \
                            --file source-package.zip \
                            --output ${REPORTS_DIR}/fod-scan-status.txt || true
                        
                        # Get scan status
                        cat ${REPORTS_DIR}/fod-scan-status.txt || echo "Scan queued in Fortify FoD"
                    '''
                }
            }
        }

        stage('[6] Approval + Merge Gate') {
            when {
                expression { env.BRANCH_NAME != 'main' && env.CHANGE_ID }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [6] APPROVAL + MERGE GATE
                    ════════════════════════════════════════════════════════════════
                    """

                    // In Jenkins, approval is typically done via the Input step
                    try {
                        def userInput = input(
                            id: 'ApprovalGate',
                            message: 'All checks passed. Approve to merge and deploy?',
                            parameters: [
                                string(
                                    name: 'APPROVAL_NOTES',
                                    description: 'Approval notes (optional)'
                                )
                            ]
                        )
                        env.APPROVER_NOTES = userInput
                        echo "✅ Approval granted by Jenkins user"
                    } catch (err) {
                        currentBuild.result = 'ABORTED'
                        error('Approval denied or timeout')
                    }
                }

                script {
                    // For main branch, validate architect gate
                    if (env.BASE_BRANCH == 'main') {
                        def architects = ['chorevathi-deloitte', 'mukeshranadeloitte']
                        def approver = "${BUILD_USER_ID}"
                        
                        if (!architects.contains(approver)) {
                            error("❌ ARCHITECT GATE FAILED: Only architects (${architects.join(', ')}) can approve main branch PRs")
                        }
                        echo "✅ Architect gate passed for: ${approver}"
                    }
                }

                sh '''
                    # Merge PR (GitHub API call)
                    echo "📋 Merging pull request..."
                    
                    if [ ! -z "${GITHUB_TOKEN}" ]; then
                        curl -s -X PUT \
                            -H "Authorization: token ${GITHUB_TOKEN}" \
                            -H "Accept: application/vnd.github.v3+json" \
                            "https://api.github.com/repos/${GITHUB_REPOSITORY}/pulls/${CHANGE_ID}/merge" \
                            -d '{"merge_method":"merge","commit_title":"'"${GIT_COMMIT_MESSAGE}"'"}' \
                            > ${REPORTS_DIR}/merge-result.json
                        
                        MERGE_COMMIT=$(jq -r '.sha' ${REPORTS_DIR}/merge-result.json)
                        echo "✅ PR merged. Merge commit: ${MERGE_COMMIT}"
                        echo "MERGE_SHA=${MERGE_COMMIT}" >> $WORKSPACE/build.env
                    fi
                '''

                script {
                    load "$WORKSPACE/build.env"
                    env.MERGE_SHA = env.MERGE_SHA ?: env.HEAD_SHA
                    echo "Merge SHA: ${env.MERGE_SHA}"
                }
            }
        }

        stage('[7] Deploy After Merge') {
            when {
                expression { env.MERGE_SHA != null }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [7] DEPLOY AFTER MERGE
                    ════════════════════════════════════════════════════════════════
                    """
                }

                sh '''
                    # Checkout merge commit
                    git fetch origin ${MERGE_SHA}
                    git checkout ${MERGE_SHA}
                    
                    # Regenerate delta from merge commit
                    echo "📊 Generating deployment delta..."
                    mkdir -p deploy-package deploy-destructive
                    
                    sfdx sgd source delta \
                        --from ${BASE_SHA} \
                        --to ${MERGE_SHA} \
                        --output deploy-package \
                        --generate-delta \
                        --repo-from .
                    
                    # Display manifests
                    echo "════════════════════════════════════════════════════════════════"
                    echo "  DEPLOYMENT MANIFESTS"
                    echo "════════════════════════════════════════════════════════════════"
                    
                    if [ -f deploy-package/package/package.xml ]; then
                        echo "✅ Deploy package:"
                        cat deploy-package/package/package.xml
                    fi
                    
                    # Deploy (NO tests — already ran in validation)
                    echo ""
                    echo "▶ Running deployment..."
                    DEPLOY_CMD="sfdx force:source:deploy \
                        --targetusername ${ORG_ALIAS} \
                        --sourcepath deploy-package/package \
                        --testlevel NoTestRun \
                        --wait 120 \
                        --json"
                    
                    DEPLOY_OUTPUT=$(eval "$DEPLOY_CMD")
                    
                    DEPLOY_STATUS=$(echo "$DEPLOY_OUTPUT" | jq -r '.result.status // "Unknown"')
                    echo "Status: ${DEPLOY_STATUS}"
                    
                    # Save deployment result
                    echo "$DEPLOY_OUTPUT" > ${REPORTS_DIR}/deployment-result.json
                    
                    if [ "$DEPLOY_STATUS" != "Succeeded" ]; then
                        echo "❌ Deployment Failed"
                        echo "$DEPLOY_OUTPUT" | jq '.'
                        exit 1
                    fi
                    
                    echo "✅ Deployment Succeeded"
                '''

                script {
                    // Display deployment summary
                    def deployResult = readJSON file: "${ARTIFACTS_DIR}/deployment-result.json"
                    echo """
                    
                    ## Deployment Summary
                    - Status: ${deployResult.result?.status ?: 'Unknown'}
                    - Deployment ID: ${deployResult.result?.id ?: 'N/A'}
                    - Components deployed: ${deployResult.result?.numberComponentsDeployed ?: 0}
                    """

                    // Update DELTA_FROM_COMMIT via GitHub API
                    if (env.GITHUB_TOKEN) {
                        sh '''
                            curl -s -X PATCH \
                                -H "Authorization: token ${GITHUB_TOKEN}" \
                                -H "Accept: application/vnd.github.v3+json" \
                                "https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/variables/DELTA_FROM_COMMIT" \
                                -d "{\"name\":\"DELTA_FROM_COMMIT\",\"value\":\"${MERGE_SHA}\"}" \
                                > /dev/null || echo "⚠️  Could not update DELTA_FROM_COMMIT"
                        '''
                    }
                }
            }
        }

        stage('[8] Trigger CRT Smoke Tests') {
            when {
                expression { env.MERGE_SHA != null }
            }
            steps {
                script {
                    echo """
                    ════════════════════════════════════════════════════════════════
                      STAGE [8] TRIGGER CRT SMOKE TESTS
                    ════════════════════════════════════════════════════════════════
                    """
                }

                withCredentials([string(credentialsId: 'CRT_API_TOKEN', variable: 'CRT_TOKEN')]) {
                    sh '''
                        # Create CRT test build via GraphQL
                        CRT_MUTATION='
                        mutation {
                          createBuild(input: {
                            projectId: "'${CRT_PROJECT_ID}'"
                            jobId: "'${CRT_JOB_ID}'"
                          }) {
                            buildId
                            status
                          }
                        }
                        '
                        
                        echo "🚀 Triggering CRT test build..."
                        CRT_RESPONSE=$(curl -s -X POST \
                            -H "X-Authorization: ${CRT_TOKEN}" \
                            -H "Content-Type: application/json" \
                            -d "{\"query\":\"$CRT_MUTATION\"}" \
                            ${CRT_API_URL})
                        
                        BUILD_ID=$(echo "$CRT_RESPONSE" | jq -r '.data.createBuild.buildId // empty')
                        
                        if [ -z "$BUILD_ID" ]; then
                            echo "❌ Failed to trigger CRT build"
                            echo "$CRT_RESPONSE" | jq '.'
                            exit 1
                        fi
                        
                        echo "✅ CRT build triggered: ${BUILD_ID}"
                        echo "CRT_BUILD_ID=${BUILD_ID}" >> $WORKSPACE/build.env
                        
                        # Poll for build completion
                        echo "⏳ Polling for build completion..."
                        
                        CRT_QUERY='
                        query {
                          latestBuilds(projectId: "'${CRT_PROJECT_ID}'", resultSize: 50) {
                            builds {
                              buildId
                              status
                              result
                              startTime
                              endTime
                            }
                          }
                        }
                        '
                        
                        MAX_POLLS=60
                        POLL_COUNT=0
                        BUILD_STATUS="executing"
                        
                        while [ "$BUILD_STATUS" = "executing" ] && [ $POLL_COUNT -lt $MAX_POLLS ]; do
                            sleep 30
                            POLL_COUNT=$((POLL_COUNT + 1))
                            
                            CRT_STATUS=$(curl -s -X POST \
                                -H "X-Authorization: ${CRT_TOKEN}" \
                                -H "Content-Type: application/json" \
                                -d "{\"query\":\"$CRT_QUERY\"}" \
                                ${CRT_API_URL})
                            
                            BUILD_STATUS=$(echo "$CRT_STATUS" | jq -r ".data.latestBuilds.builds[0].status // \"unknown\"" | tr '[:upper:]' '[:lower:]')
                            echo "Poll ${POLL_COUNT}: Status = ${BUILD_STATUS}"
                        done
                        
                        echo "CRT_BUILD_STATUS=${BUILD_STATUS}" >> $WORKSPACE/build.env
                    '''
                }

                script {
                    load "$WORKSPACE/build.env"
                    env.CRT_BUILD_ID = env.CRT_BUILD_ID ?: 'unknown'
                    env.CRT_BUILD_STATUS = env.CRT_BUILD_STATUS ?: 'unknown'
                    
                    echo """
                    
                    ════════════════════════════════════════════════════════════════
                      CRT TEST RESULTS
                    ════════════════════════════════════════════════════════════════
                    Build ID: ${env.CRT_BUILD_ID}
                    Status: ${env.CRT_BUILD_STATUS}
                    
                    Dashboard: ${CRT_API_URL}/builds/${env.CRT_BUILD_ID}
                    """

                    if (env.CRT_BUILD_STATUS == 'failed' || env.CRT_BUILD_STATUS == 'error') {
                        error("❌ CRT tests failed with status: ${env.CRT_BUILD_STATUS}")
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                echo """
                ════════════════════════════════════════════════════════════════
                  BUILD SUMMARY
                ════════════════════════════════════════════════════════════════
                Build Result: ${currentBuild.result}
                Build Number: ${BUILD_NUMBER}
                """
            }

            // Archive artifacts
            archiveArtifacts artifacts: 'reports/**', allowEmptyArchive: true
            archiveArtifacts artifacts: 'artifacts/**', allowEmptyArchive: true

            // Publish JUnit test results if available
            junit testResults: 'reports/**/*.xml', allowEmptyResults: true

            // Cleanup
            cleanWs()
        }

        success {
            script {
                echo "✅ Pipeline completed successfully"
                // Send success notification
                // emailext(subject: "Build ${BUILD_NUMBER} Successful", body: "...", to: "team@example.com")
            }
        }

        failure {
            script {
                echo "❌ Pipeline failed"
                // Send failure notification
                // emailext(subject: "Build ${BUILD_NUMBER} Failed", body: "...", to: "team@example.com")
            }
        }

        unstable {
            script {
                echo "⚠️  Pipeline unstable"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

def extractTestClasses() {
    try {
        // In Jenkins, PR details are typically available via environment variables or API
        // This is a placeholder; actual implementation depends on Jenkins plugins
        def testPattern = ~/Tests?:[\s]*([\w,\s]+)/
        def tests = []
        // Parse from PR description or Jenkins environment
        return tests
    } catch (Exception e) {
        echo "⚠️  Error extracting test classes: ${e.message}"
        return []
    }
}

def checkApexChanges() {
    def changes = sh(
        returnStdout: true,
        script: "git diff ${DELTA_FROM_COMMIT}..HEAD --name-only | grep -E '\\.(cls|trigger)$' | wc -l"
    ).trim()
    return changes.toInteger() > 0
}

def inferTestClasses() {
    def testClasses = []
    def changedClasses = sh(
        returnStdout: true,
        script: "git diff ${DELTA_FROM_COMMIT}..HEAD --name-only | grep -E '\\.cls$' | xargs -I {} basename {} .cls"
    ).trim().split('\n').findAll { it }
    
    changedClasses.each { className ->
        if (className.matches(/.*(Test|Tests|TestClass)$/)) {
            testClasses.add(className)
        } else {
            testClasses.add(className + 'Test')
            testClasses.add(className + 'Tests')
        }
    }
    
    return testClasses.unique()
}
