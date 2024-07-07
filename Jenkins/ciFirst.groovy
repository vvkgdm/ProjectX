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
                    dir('ProjectX') {
                        // Add debugging for changed files
                        def changedFiles = sh(script: 'git diff-tree --no-commit-id --name-only -r HEAD', returnStdout: true).trim().split('\n')
                        echo "Changed files: ${changedFiles}"
                        def services = ['frontend', 'cartservice', 'productcatalogservice', 'currencyservice', 'paymentservice', 'shippingservice', 'emailservice', 'checkoutservice', 'recommendationservice', 'adservice', 'loadgenerator', 'shoppingassistantservice']
                        def relevantServices = services.findAll { service -> 
                            changedFiles.any { it.contains("SourceCode/${service}/") }
                        }
                        echo "Relevant services: ${relevantServices}"
                        env.CHANGED_SERVICES = relevantServices.join(',')
                        if (env.CHANGED_SERVICES.isEmpty()) {
                            error("No relevant services changed.")
                        }
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
          
