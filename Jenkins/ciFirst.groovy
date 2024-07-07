pipeline {
    agent any

    environment {
        GITHUB_REPO = 'vvkgdm/ProjectX'
        GITHUB_HELM_REPO = 'vvkgdm/ProjectXHelm'
        BRANCH_NAME = 'dit'
        HELM_BRANCH_NAME = 'main'
        NEXUS_REPO = 'http://54.89.16.64:8082/repository/docker-private'
        NEXUS_URL = 'http://54.89.16.64:8081/'
        SONAR_URL = 'http://54.89.16.64:9000/'
        DATE_TAG = "${new Date().format('yyyyMMddHHmmss')}"
        GIT_CREDS = credentials('githubID')
        SCANNER_HOME = tool 'sonar-scanner'
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    checkout([$class: 'GitSCM',
                              branches: [[name: "*/${BRANCH_NAME}"]],
                              userRemoteConfigs: [[url: "https://github.com/${GITHUB_REPO}", credentialsId: "${GIT_CREDS}"]],
                              extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ProjectX']]])
                }
            }
        }

        stage('Identify Changed Services') {
            steps {
                script {
                    // Add debugging for changed files
                    def changedFiles = sh(script: 'git diff-tree --no-commit-id --name-only -r HEAD', returnStdout: true).trim().split('\n')
                    echo "Changed files: ${changedFiles}"
                    def services = ['frontend', 'cartservice', 'productcatalogservice', 'currencyservice', 'paymentservice', 'shippingservice', 'emailservice', 'checkoutservice', 'recommendationservice', 'adservice', 'loadgenerator', 'shoppingassistantservice']
                    def relevantServices = services.findAll { service -> 
                        changedFiles.any { it.contains(service) }
                    }
                    echo "Relevant services: ${relevantServices}"
                    env.CHANGED_SERVICES = relevantServices.join(',')
                    if (env.CHANGED_SERVICES.isEmpty()) {
                        error("No relevant services changed.")
                    }
                }
            }
        }

        stage('Trivy Scan') {
            when {
                expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != '' }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        dir("ProjectX/SourceCode/${service}") {
                            sh '''
                                docker run --rm -v $(pwd):/workspace -w /workspace aquasec/trivy:latest fs --format table -o trivy-fs-report.html .
                            '''
                        }
                    }
                }
            }
        }

        stage('Sonar Scan') {
            when {
                expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != '' }
            }
            steps {
                script {
                    def services = env.CHANGED_SERVICES.split(',')
                    services.each { service ->
                        dir("ProjectX/SourceCode/${service}") {
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
                expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != '' }
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
                                sh "docker build -t ${NEXUS_REPO}/${service}:${DATE_TAG} ."
                                sh "docker push ${NEXUS_REPO}/${service}:${DATE_TAG}"
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
                expression { env.CHANGED_SERVICES != null && env.CHANGED_SERVICES != '' }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'githubID', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                    script {
                        def services = env.CHANGED_SERVICES.split(',')
                        services.each { service ->
                            dir("ProjectX/SourceCode/${service}") {
                                sh """
                                    git clone https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${GITHUB_HELM_REPO}.git
                                    cd ${GITHUB_HELM_REPO}
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
