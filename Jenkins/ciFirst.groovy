pipeline {
    agent any

    environment {
        GITHUB_REPO = 'vvkgdm/ProjectX'
        GITHUB_HELM_REPO = 'vvkgdm/ProjectXHelm'
        BRANCH_NAME = 'dit'
        HELM_BRANCH_NAME = 'main'
        NEXUS_REPO = 'http://54.89.16.64:8082/repository/docker-private'
        NEXUS_URL = 'http://54.89.16.64:8081/'
        BUILD_NUMBER_TAG = "${env.BUILD_NUMBER}"
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
                        dir(service) {
                            sh '''
                                docker run --rm -v $(pwd):/workspace -w /workspace aquasec/trivy:latest fs --format table -o trivy-fs-report.html .
                            '''
                        }
                    }
                }
            }
        }

        /*stage('Sonar Scan') {
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
        } */

        stage('Build and Push Docker Images') {
            when {
                expression { env.CHANGED_SERVICES != null }
            }
            steps {
                script {
                    echo "CHANGED_SERVICES: ${env.CHANGED_SERVICES}"
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        echo "Processing service: ${service}"
                        dir("ProjectX/SourceCode/${service}") {
                            echo "Current directory: ${pwd()}"
                            sh 'ls -la'
                            if (fileExists('Dockerfile')) {
                                echo "Building and pushing Docker image for ${service}"
                                sh "docker build -t ${NEXUS_REPO}/${service}:${BUILD_NUMBER_TAG} ."
                                sh "docker push ${NEXUS_REPO}/${service}:${BUILD_NUMBER_TAG}"
                            } else {
                                echo "No Dockerfile found in ${service}, skipping."
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
                withCredentials([usernamePassword(credentialsId: 'githubID', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                    script {
                        def services = env.CHANGED_SERVICES.split(',')
                        services.each { service ->
                            dir(service) {
                                sh """
                                    git clone https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${GITHUB_HELM_REPO}.git
                                    cd ${GITHUB_HELM_REPO}
                                    sed -i 's/tag:.*/tag: ${BUILD_NUMBER_TAG}/g' values-${BRANCH_NAME}.yaml
                                    git commit -am 'Update image tag to ${BUILD_NUMBER_TAG}'
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
