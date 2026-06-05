// RagVault — 사내 서비스 Jenkins Declarative Pipeline
// 빌드 → ECR 이미지 푸시 → 배포 서버에서 docker compose up
//
// 배포 방식: SSH 또는 SSM Run Command로 대상 서버에서 docker compose 실행
// 대상 서버: DEPLOY_HOST 환경변수로 지정

pipeline {
    agent any

    parameters {
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Docker 이미지 태그 (비우면 git commit SHA 자동 사용)'
        )
        string(
            name: 'DEPLOY_HOST',
            defaultValue: '',
            description: '배포 대상 서버 (예: deploy@192.168.1.10). 비우면 빌드·푸시만 실행.'
        )
        booleanParam(
            name: 'COMPOSE_ONLY',
            defaultValue: false,
            description: 'true 시 JAR 빌드·Docker 푸시 스킵, docker compose 재배포만 실행'
        )
    }

    environment {
        ECR_REGISTRY    = credentials('ECR_REGISTRY')   // 레지스트리 주소
        DISCORD_WEBHOOK = credentials('DISCORD_WEBHOOK')
        AWS_REGION      = 'ap-northeast-2'
        ECR_IMAGE_NAME  = 'rag-backend'
        COMPOSE_FILE    = 'rag-infra/docker-compose.prod.yml'
    }

    options {
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        ansiColor('xterm')
    }

    stages {

        // ── Stage 1: Checkout + 이미지 태그 결정 ──────────────────────────────
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.RESOLVED_IMAGE_TAG = params.IMAGE_TAG?.trim()
                        ? params.IMAGE_TAG.trim()
                        : sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()

                    echo "==> Image Tag  : ${env.RESOLVED_IMAGE_TAG}"
                    echo "==> Deploy Host: ${params.DEPLOY_HOST ?: '(없음 — 빌드·푸시만)'}"
                    echo "==> Compose Only: ${params.COMPOSE_ONLY}"
                }
            }
        }

        // ── Stage 2: Gradle Build (JAR) ────────────────────────────────────────
        stage('Build JAR') {
            when { expression { !params.COMPOSE_ONLY } }
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

        // ── Stage 3: Docker Build & Push ───────────────────────────────────────
        stage('Docker Build & Push') {
            when { expression { !params.COMPOSE_ONLY } }
            steps {
                script {
                    def ecrRepo   = "${env.ECR_REGISTRY}/${env.ECR_IMAGE_NAME}"
                    def imageTag  = env.RESOLVED_IMAGE_TAG

                    sh """
                        aws ecr get-login-password --region ${env.AWS_REGION} \
                            | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}
                    """

                    sh """
                        DOCKER_BUILDKIT=1 docker build \
                            --build-arg JAR_FILE=build/libs/*.jar \
                            --cache-from ${ecrRepo}:latest \
                            -t ${ecrRepo}:${imageTag} \
                            -t ${ecrRepo}:latest \
                            rag-backend/
                    """

                    sh """
                        docker push ${ecrRepo}:${imageTag}
                        docker push ${ecrRepo}:latest
                    """

                    echo "==> ECR 푸시 완료: ${ecrRepo}:${imageTag}"
                }
            }
        }

        // ── Stage 4: Docker Compose 배포 ──────────────────────────────────────
        // DEPLOY_HOST가 지정된 경우에만 실행
        // TODO: 배포 환경 확정 후 SSH / SSM / 기타 방식으로 구체화
        stage('Deploy') {
            when { expression { params.DEPLOY_HOST?.trim() } }
            steps {
                script {
                    def host     = params.DEPLOY_HOST.trim()
                    def imageTag = env.RESOLVED_IMAGE_TAG

                    // compose 파일 업로드 후 docker compose up
                    sh """
                        scp -o StrictHostKeyChecking=no \
                            ${env.COMPOSE_FILE} \
                            ${host}:/opt/ragvault/docker-compose.prod.yml

                        ssh -o StrictHostKeyChecking=no ${host} \
                            "IMAGE_TAG=${imageTag} docker compose \
                             -f /opt/ragvault/docker-compose.prod.yml \
                             up -d --remove-orphans && \
                             docker compose -f /opt/ragvault/docker-compose.prod.yml ps"
                    """

                    echo "==> 배포 완료: ${host}"
                }
            }
        }

    }

    post {
        success {
            script {
                def tag = env.RESOLVED_IMAGE_TAG ?: 'N/A'
                def deployed = params.DEPLOY_HOST?.trim() ? " → ${params.DEPLOY_HOST}" : ' (빌드·푸시만)'
                def payload = """{"embeds":[{"title":":white_check_mark: RagVault 빌드 성공","color":3066993,"fields":[{"name":"Image Tag","value":"${tag}","inline":true},{"name":"Build","value":"#${BUILD_NUMBER}","inline":true},{"name":"Deploy","value":"${deployed}","inline":false}]}]}"""
                sh "curl -s -X POST '${env.DISCORD_WEBHOOK}' -H 'Content-Type: application/json' -d '${payload}' || true"
            }
        }

        failure {
            script {
                def payload = """{"embeds":[{"title":":x: RagVault 빌드 실패","color":15158332,"fields":[{"name":"Build","value":"#${BUILD_NUMBER}","inline":true}],"description":"로그: ${BUILD_URL}console"}]}"""
                sh "curl -s -X POST '${env.DISCORD_WEBHOOK}' -H 'Content-Type: application/json' -d '${payload}' || true"
            }
        }

        always { cleanWs() }
    }

} // end pipeline
