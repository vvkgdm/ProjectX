pipeline {
    agent any

    environment {
        GITHUB_REPO = 'vvkgdm/ProjectX'
        BRANCH_NAME = 'dit'
        NEXUS_REPO = 'http://54.92.241.126:8081/repository/docker-repo'
        NEXUS_URL = 'http://54.92.241.126:8081/'
        SONAR_URL = 'http://54.92.241.126:9000/'
        DATE_TAG = "${new Date().format('yyyyMMddHHmmss')}"
        GIT_CREDS = credentials('githubID')
        SCANNER_HOME = tool 'sonar-scanner'
    }

    stages {
        stage('Checkout') {
            steps {
                git url: "https://github.com/${GITHUB_REPO}", branch: "${BRANCH_NAME}", credentialsId: "${GIT_CREDS}"
            }
        }

        stage('Identify Changed Services') {
            steps {
                script {
                    def changedFiles = sh(script: 'git diff-tree --no-commit-id --name-only -r HEAD', returnStdout: true).trim().split('\n')
                    def services = ['frontend', 'cartservice', 'productcatalogservice', 'currencyservice', 'paymentservice', 'shippingservice', 'emailservice', 'checkoutservice', 'recommendationservice', 'adservice', 'loadgenerator', 'shoppingassistantservice']
                    env.CHANGED_SERVICES = services.findAll { service -> 
                        changedFiles.any { it.contains(service) }
                    }.join(',')
                    if (env.CHANGED_SERVICES.isEmpty()) {
                        error("No relevant services changed.")
                    }
                }
            }
        }

        stage('Trivy Scan') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        docker.image('aquasec/trivy:latest').inside {
                            dir(service) {
                                sh 'trivy fs --format table -o trivy-fs-report.html .'
                            }
                        }
                    }
                }
            }
        }

        stage('Sonar Scan') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        dir(service) {
                            withSonarQubeEnv('sonar') {
                                sh """
                                    ${SCANNER_HOME}/bin/sonar-scanner \
                                    -Dsonar.host.url=${SONAR_URL} \
                                    -Dsonar.projectKey=${service} \
                                    -Dsonar.projectName=${service}
                                """
                            }
                        }
                    }
                }
            }
        }

        stage('Build and Push Docker Images') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        dir(service) {
                            if (fileExists('Dockerfile')) {
                                sh "docker build -t ${NEXUS_REPO}/${service}:${DATE_TAG} ."
                                sh "docker push ${NEXUS_REPO}/${service}:${DATE_TAG}"
                            }
                        }
                    }
                }
            }
        }

        stage('Inline Scanning') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        sh "sysdig-inline-scan --docker-image ${NEXUS_REPO}/${service}:${DATE_TAG}"
                        sh "trivy image ${NEXUS_REPO}/${service}:${DATE_TAG}"
                    }
                }
            }
        }

        stage('Update Helm Values') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'githubID', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                    script {
                        def services = env.CHANGED_SERVICES.split(',')
                        services.each { service ->
                            dir(service) {
                                sh """
                                    git clone https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${GITHUB_REPO}.git
                                    cd ${GITHUB_REPO}
                                    sed -i 's/tag:.*/tag: ${DATE_TAG}/g' values-${BRANCH_NAME}.yaml
                                    git commit -am 'Update image tag to ${DATE_TAG}'
                                    git push
                                """
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}

def getDockerImageForService(service) {
    switch (service) {
        case 'frontend':
        case 'productcatalogservice':
        case 'shippingservice':
        case 'checkoutservice':
            return 'golang:1.16'
        case 'cartservice':
            return 'mcr.microsoft.com/dotnet/sdk:5.0'
        case 'currencyservice':
        case 'paymentservice':
            return 'node:14'
        case 'emailservice':
        case 'recommendationservice':
        case 'loadgenerator':
            return 'python:3.8'
        case 'adservice':
            return 'openjdk:11'
        default:
            return 'alpine'
    }
}
