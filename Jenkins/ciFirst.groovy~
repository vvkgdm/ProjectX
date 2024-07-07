pipeline {
    agent any

    environment {
        GITHUB_REPO = 'vvkgdm/ProjectX'
        BRANCH_NAME = 'master'
        NEXUS_REPO = 'your-nexus-repo'
        NEXUS_URL = 'your-nexus-url'
        DATE_TAG = "${new Date().format('yyyyMMddHHmmss')}"
        DOCKER_CREDS = credentials('docker-credentials-id')
        GIT_CREDS = credentials('git-credentials-id')
        SONAR_SCANNER = 'SonarQubeScanner'
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
                    def services = ['frontend', 'cartservice', 'productcatalogservice', 'currencyservice', 'paymentservice', 'shippingservice', 'emailservice', 'checkoutservice', 'recommendationservice', 'adservice', 'loadgenerator']
                    env.CHANGED_SERVICES = services.findAll { service -> 
                        changedFiles.any { it.contains(service) }
                    }.join(',')
                    if (env.CHANGED_SERVICES.isEmpty()) {
                        error("No relevant services changed.")
                    }
                }
            }
        }

        stage('Vulnerability Scan') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        dir(service) {
                            withSonarQubeEnv('SonarQube') {
                                sh "${SONAR_SCANNER} -Dsonar.projectKey=${env.JOB_NAME}-${service} -Dsonar.sources=."
                            }
                            sh 'trivy fs .'
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
                                docker.build("${NEXUS_REPO}/${service}:${DATE_TAG}").push()
                            }
                        }
                    }
                }
            }
        }

        stage('Update Helm Values') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'helm-git-credentials-id', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                    script {
                        def services = env.CHANGED_SERVICES.split(',')
                        services.each { service ->
                            dir(service) {
                                sh """
                                    git clone https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${HELM_REPO}.git
                                    cd ${HELM_REPO}
                                    sed -i 's/tag:.*/tag: ${DATE_TAG}/g' values.yaml
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

