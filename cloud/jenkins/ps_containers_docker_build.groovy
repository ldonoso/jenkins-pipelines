void build(String IMAGE_POSTFIX){
    sh """
        cd ./source/
        docker build --no-cache --squash --progress plain \
            -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
            -f ./orchestrator/Dockerfile ./orchestrator
    """
}
void checkImageForDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            sg docker -c '
                docker login -u '${USER}' -p '${PASS}'

                TrityHightLog="$WORKSPACE/trivy-hight-percona-server-mysql-operator-${IMAGE_POSTFIX}.log"
                TrityCriticaltLog="$WORKSPACE/trivy-critical-percona-server-mysql-operator-${IMAGE_POSTFIX}.log"
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image -o \$TrityHightLog --ignore-unfixed --exit-code 0 --severity HIGH \
                    perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}

                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image -o \$TrityCriticaltLog --ignore-unfixed --exit-code 0 --severity CRITICAL \
                    perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}

                if [ ! -s \$TrityHightLog ]; then
                    rm -rf \$TrityHightLog
                fi

                if [ ! -s \$TrityCriticaltLog ]; then
                    rm -rf \$TrityCriticaltLog
                fi
            '

        """
    }
}
void checkImageForCVE(String IMAGE_SUFFIX){
    try {
        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
            sh """
                IMAGE_NAME='percona-server-mysql-operator'
                docker run -v \$(pwd):/tmp/pgo --rm quay.io/sysdig/secure-inline-scan:2 perconalab/\$IMAGE_NAME:${IMAGE_SUFFIX} --sysdig-token '${SYSDIG_API_KEY}' --sysdig-url https://us2.app.sysdig.com -r /tmp/pgo
            """
        }
    } catch (error) {
        echo "${IMAGE_SUFFIX} has some CVE error(s) please check the reports."
        currentBuild.result = 'FAILURE'
    }
}
void pushImageToDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            sg docker -c '
                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p /home/ec2-user/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                docker trust sign perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}
                docker push perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}
                docker logout
            '
        """
    }
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
    }
    agent {
         label 'docker'
    }
    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                """
                stash includes: "cloud/**", name: "cloud"
            }
        }
        stage('Build ps docker images') {
            steps {
                sh '''
                    sudo rm -rf cloud
                '''
                unstash "cloud"
                sh """
                   sudo rm -rf source
                   export GIT_REPO=$GIT_PD_REPO
                   export GIT_BRANCH=$GIT_PD_BRANCH
                   ./cloud/local/checkout
                """
                retry(3) {
                    build('orchestrator')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDocker('orchestrator')
            }
        }
        stage('Check Docker images') {
            steps {
                checkImageForDocker('orchestrator')
                sh '''
                   CRITICAL=$(ls trivy-critical-*) || true
                   if [ -n "$CRITICAL" ]; then
                       exit 1
                   fi
                '''
            }
        }
        stage('Check Docker images for CVE issues') {
            steps {
                checkImageForCVE('orchestrator')
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PG docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
