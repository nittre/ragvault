// RAG SaaS — Cross-Account Jenkins Declarative Pipeline
// 우리 회사 ECR에 이미지 푸시 → 고객사 계정 RagOperatorRole AssumeRole → k3s Helm 배포
// kubeconfig: SSM Parameter Store /rag/{CUSTOMER_ID}/kubeconfig (고객사 계정)

pipeline {
    agent any

    parameters {
        string(
            name: 'CUSTOMER_ID',
            defaultValue: '',
            description: '고객사 ID (영소문자·숫자·하이픈, 예: acme-corp)'
        )
        string(
            name: 'CUSTOMER_ACCOUNT_ID',
            defaultValue: '',
            description: '고객사 AWS 계정 ID (12자리 숫자)'
        )
        choice(
            name: 'ENVIRONMENT',
            choices: ['prod', 'staging', 'dev'],
            description: '배포 환경 (default: prod)'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Docker 이미지 태그 (비우면 git commit SHA 자동 사용)'
        )
        booleanParam(
            name: 'HELM_ONLY',
            defaultValue: false,
            description: 'true 시 JAR 빌드·Docker 푸시 스킵, Helm upgrade만 실행'
        )
    }

    environment {
        ECR_REGISTRY    = credentials('ECR_REGISTRY')   // 우리 회사 ECR (예: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com)
        DISCORD_WEBHOOK = credentials('DISCORD_WEBHOOK')
        AWS_REGION      = 'ap-northeast-2'
        ECR_IMAGE_NAME  = 'rag-backend'
        KUBECONFIG_PATH = "${WORKSPACE}/.kubeconfig-${BUILD_NUMBER}"
    }

    options {
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        ansiColor('xterm')
    }

    stages {

        // ── Stage 1: Checkout + 파라미터 검증 ────────────────────────────────
        stage('Checkout') {
            steps {
                checkout scm

                script {
                    // 필수 파라미터 검증
                    if (!params.CUSTOMER_ID?.trim()) {
                        error('CUSTOMER_ID 는 필수 파라미터입니다.')
                    }
                    if (!params.CUSTOMER_ACCOUNT_ID?.trim()) {
                        error('CUSTOMER_ACCOUNT_ID 는 필수 파라미터입니다.')
                    }
                    if (!(params.CUSTOMER_ACCOUNT_ID ==~ /^\d{12}$/)) {
                        error('CUSTOMER_ACCOUNT_ID 는 12자리 숫자여야 합니다.')
                    }

                    // IMAGE_TAG 결정: 비어있으면 git commit SHA (short)
                    env.RESOLVED_IMAGE_TAG = params.IMAGE_TAG?.trim()
                        ? params.IMAGE_TAG.trim()
                        : sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()

                    env.RAG_OPERATOR_ROLE = "arn:aws:iam::${params.CUSTOMER_ACCOUNT_ID}:role/RagOperatorRole"

                    echo "==> Customer    : ${params.CUSTOMER_ID}"
                    echo "==> Account     : ${params.CUSTOMER_ACCOUNT_ID}"
                    echo "==> Environment : ${params.ENVIRONMENT}"
                    echo "==> Image Tag   : ${env.RESOLVED_IMAGE_TAG}"
                    echo "==> Helm Only   : ${params.HELM_ONLY}"
                    echo "==> AssumeRole  : ${env.RAG_OPERATOR_ROLE}"
                }
            }
        }

        // ── Stage 2: Gradle Build (JAR, test skip) ────────────────────────────
        stage('Build JAR') {
            when {
                expression { !params.HELM_ONLY }
            }
            steps {
                dir('rag-backend') {
                    sh '''
                        chmod +x ./gradlew
                        ./gradlew clean bootJar -x test \
                            --no-daemon \
                            --parallel \
                            --build-cache
                    '''
                }
                echo "==> JAR 빌드 완료"
            }
        }

        // ── Stage 3: Docker Build & Push (우리 회사 ECR) ─────────────────────
        stage('Docker Build & Push') {
            when {
                expression { !params.HELM_ONLY }
            }
            steps {
                script {
                    def ecrRepo   = "${env.ECR_REGISTRY}/${env.ECR_IMAGE_NAME}"
                    def imageTag  = env.RESOLVED_IMAGE_TAG
                    def fullImage = "${ecrRepo}:${imageTag}"

                    // 우리 회사 계정 ECR 로그인 (Jenkins 기본 자격증명)
                    sh """
                        aws ecr get-login-password --region ${env.AWS_REGION} \
                            | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}
                    """

                    // Docker 빌드 (BuildKit, 레이어 캐시 활용)
                    sh """
                        DOCKER_BUILDKIT=1 docker build \
                            --build-arg JAR_FILE=build/libs/*.jar \
                            --cache-from ${ecrRepo}:latest \
                            -t ${fullImage} \
                            -t ${ecrRepo}:latest \
                            rag-backend/
                    """

                    // ECR 푸시
                    sh """
                        docker push ${fullImage}
                        docker push ${ecrRepo}:latest
                    """

                    echo "==> ECR 푸시 완료: ${fullImage}"
                }
            }
        }

        // ── Stage 4: Assume Role → 고객사 계정 임시 자격증명 획득 ──────────────
        stage('Assume Role') {
            steps {
                script {
                    def assumeOutput = sh(
                        script: """
                            aws sts assume-role \
                                --role-arn "${env.RAG_OPERATOR_ROLE}" \
                                --role-session-name "rag-deploy-${params.CUSTOMER_ID}-${BUILD_NUMBER}" \
                                --external-id "${params.CUSTOMER_ID}" \
                                --duration-seconds 3600 \
                                --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
                                --output text
                        """,
                        returnStdout: true
                    ).trim()

                    def creds = assumeOutput.split(/\s+/)
                    if (creds.length < 3) {
                        error("AssumeRole 실패: 자격증명을 파싱하지 못했습니다. (출력: ${assumeOutput})")
                    }

                    // 고객사 계정 임시 자격증명 (마스킹은 Jenkins Credentials 미사용이므로 수동 관리)
                    env.CUSTOMER_AWS_ACCESS_KEY_ID     = creds[0]
                    env.CUSTOMER_AWS_SECRET_ACCESS_KEY = creds[1]
                    env.CUSTOMER_AWS_SESSION_TOKEN     = creds[2]

                    echo "==> AssumeRole 완료: ${env.RAG_OPERATOR_ROLE}"
                }
            }
        }

        // ── Stage 5: kubeconfig 취득 + 4개 Helm 차트 배포 ────────────────────
        stage('Helm Upgrade') {
            steps {
                script {
                    def namespace = 'rag-system'
                    def helmEnv   = params.ENVIRONMENT
                    def imageTag  = env.RESOLVED_IMAGE_TAG
                    def helmTimeout = '5m'

                    // SSM에서 kubeconfig 취득 (고객사 계정 자격증명 사용)
                    sh """
                        AWS_ACCESS_KEY_ID='${env.CUSTOMER_AWS_ACCESS_KEY_ID}' \
                        AWS_SECRET_ACCESS_KEY='${env.CUSTOMER_AWS_SECRET_ACCESS_KEY}' \
                        AWS_SESSION_TOKEN='${env.CUSTOMER_AWS_SESSION_TOKEN}' \
                        aws ssm get-parameter \
                            --name "/rag/${params.CUSTOMER_ID}/kubeconfig" \
                            --with-decryption \
                            --query 'Parameter.Value' \
                            --output text \
                            --region ${env.AWS_REGION} \
                            > ${env.KUBECONFIG_PATH}
                        chmod 600 ${env.KUBECONFIG_PATH}
                    """

                    // 클러스터 연결 확인
                    sh """
                        KUBECONFIG=${env.KUBECONFIG_PATH} kubectl cluster-info --request-timeout=15s
                    """

                    // 1) rag-backend
                    sh """
                        KUBECONFIG=${env.KUBECONFIG_PATH} \
                        helm upgrade --install rag-backend ./rag-infra/helm/rag-backend \
                            --namespace ${namespace} --create-namespace \
                            --set image.tag=${imageTag} \
                            --set customer.id=${params.CUSTOMER_ID} \
                            -f ./rag-infra/helm/rag-backend/values-${helmEnv}.yaml \
                            --wait --timeout ${helmTimeout}
                    """

                    // 2) open-webui
                    sh """
                        KUBECONFIG=${env.KUBECONFIG_PATH} \
                        helm upgrade --install open-webui ./rag-infra/helm/open-webui \
                            --namespace ${namespace} --create-namespace \
                            --set customer.id=${params.CUSTOMER_ID} \
                            -f ./rag-infra/helm/open-webui/values-${helmEnv}.yaml \
                            --wait --timeout ${helmTimeout}
                    """

                    // 3) ollama (GPU 노드 — AMI에 모델 사전 pull 포함)
                    sh """
                        KUBECONFIG=${env.KUBECONFIG_PATH} \
                        helm upgrade --install ollama ./rag-infra/helm/ollama \
                            --namespace ${namespace} --create-namespace \
                            --set customer.id=${params.CUSTOMER_ID} \
                            -f ./rag-infra/helm/ollama/values-${helmEnv}.yaml \
                            --wait --timeout ${helmTimeout}
                    """

                    // 4) monitoring
                    sh """
                        KUBECONFIG=${env.KUBECONFIG_PATH} \
                        helm upgrade --install monitoring ./rag-infra/helm/monitoring \
                            --namespace monitoring --create-namespace \
                            --set customer.id=${params.CUSTOMER_ID} \
                            -f ./rag-infra/helm/monitoring/values-${helmEnv}.yaml \
                            --wait --timeout ${helmTimeout}
                    """

                    echo "==> Helm 배포 완료 (rag-backend / open-webui / ollama / monitoring)"
                }
            }
        }

    } // end stages

    // ── Post: Discord 알림 + 자격증명 정리 ──────────────────────────────────
    post {
        success {
            script {
                def helmOnly = params.HELM_ONLY ? ' (Helm Only)' : ''
                def payload = """{"embeds":[{"title":":white_check_mark: RAG 배포 성공${helmOnly}","color":3066993,"fields":[{"name":"Customer","value":"${params.CUSTOMER_ID}","inline":true},{"name":"Account","value":"${params.CUSTOMER_ACCOUNT_ID}","inline":true},{"name":"Environment","value":"${params.ENVIRONMENT}","inline":true},{"name":"Image Tag","value":"${env.RESOLVED_IMAGE_TAG ?: 'N/A'}","inline":true},{"name":"Build","value":"#${BUILD_NUMBER}","inline":true},{"name":"Duration","value":"${currentBuild.durationString}","inline":true}],"description":"rag-backend / open-webui / ollama / monitoring 배포 완료"}]}"""
                sh "curl -s -X POST '${env.DISCORD_WEBHOOK}' -H 'Content-Type: application/json' -d '${payload}' || true"
            }
        }

        failure {
            script {
                def payload = """{"embeds":[{"title":":x: RAG 배포 실패","color":15158332,"fields":[{"name":"Customer","value":"${params.CUSTOMER_ID}","inline":true},{"name":"Account","value":"${params.CUSTOMER_ACCOUNT_ID}","inline":true},{"name":"Environment","value":"${params.ENVIRONMENT}","inline":true},{"name":"Build","value":"#${BUILD_NUMBER}","inline":true}],"description":"로그: ${BUILD_URL}console"}]}"""
                sh "curl -s -X POST '${env.DISCORD_WEBHOOK}' -H 'Content-Type: application/json' -d '${payload}' || true"
            }
        }

        always {
            // kubeconfig 파일 삭제 (보안)
            sh "rm -f '${env.KUBECONFIG_PATH}' || true"

            // 고객사 계정 임시 자격증명 초기화
            script {
                env.CUSTOMER_AWS_ACCESS_KEY_ID     = ''
                env.CUSTOMER_AWS_SECRET_ACCESS_KEY = ''
                env.CUSTOMER_AWS_SESSION_TOKEN     = ''
            }

            cleanWs()
        }
    }

} // end pipeline
