library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'large-amazon'
    }
    parameters {
        string(
            defaultValue: 'hotfix-1.X.x',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            choices: 'experimental\ntesting\nlaboratory',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                installDocker()

                git poll: true, branch: GIT_BRANCH, url: 'http://github.com/Percona-Lab/pmm-submodules'
                sh '''
                    git reset --hard
                    sudo git clean -xdf
                    git submodule update --init --jobs 10 \
                        sources/pmm-client/src/github.com/percona/pmm-client \
                        sources/mongodb_exporter/src/github.com/percona/mongodb_exporter \
                        sources/postgres_exporter/src/github.com/percona/postgres_exporter \
                        sources/mysqld_exporter/src/github.com/percona/mysqld_exporter \
                        sources/proxysql_exporter/src/github.com/percona/proxysql_exporter \
                        sources/qan-agent/src/github.com/percona/qan-agent \
                        sources/node_exporter/src/github.com/prometheus/node_exporter \
                        sources/pid-watchdog/src/github.com/percona/pid-watchdog \
                        sources/percona-toolkit/src/github.com/percona/percona-toolkit

                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/${DESTINATION}/${JOB_NAME}/pmm/\$(cat VERSION)/${GIT_BRANCH}/\$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
                '''
                archiveArtifacts 'uploadPath'
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Build client source') {
            steps {
                sh '''
                    sg docker -c "
                        export pmm_version=$(cat VERSION)
                        ./build/bin/build-client-source
                    "
                '''
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                uploadTarball('source')
            }
        }
        stage('Build client binary') {
            steps {
                sh '''
                    sg docker -c "
                        export pmm_version=$(cat VERSION)
                        ./build/bin/build-client-binary
                    "
                '''
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }
        stage('Build client docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                sh '''
                    sg docker -c "
                        set -o xtrace

                        export PUSH_DOCKER=1
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client:$(date -u '+%Y%m%d%H%M')

                        ./build/bin/build-client-docker

                        docker tag  \\${DOCKER_CLIENT_TAG} perconalab/pmm-client:hotfix-latest
                        docker push \\${DOCKER_CLIENT_TAG}
                        docker push perconalab/pmm-client:hotfix-latest
                        docker rmi  \\${DOCKER_CLIENT_TAG}
                        docker rmi  perconalab/pmm-client:hotfix-latest
                    "
                '''
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build client source rpm') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-srpm centos:6"'
                stash includes: 'results/srpm/pmm-client-*.src.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build client binary rpm') {
            steps {
                sh '''
                    sg docker -c "
                        ./build/bin/build-client-rpm centos:6
                        ./build/bin/build-client-rpm centos:7
                    "
                '''
                stash includes: 'results/rpm/pmm-client-*.rpm', name: 'rpms'
                uploadRPM()
            }
        }

        stage('Build client source deb') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-sdeb ubuntu:bionic"'
                stash includes: 'results/source_deb/*', name: 'debs'
                uploadDEB()
            }
        }
        stage('Build client binary debs') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-deb debian:buster"'
                sh 'sg docker -c "./build/bin/build-client-deb debian:stretch"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:bionic"'
                stash includes: 'results/deb/*.deb', name: 'debs'
                uploadDEB()
            }
        }
        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            agent {
                label 'virtualbox'
            }
            steps {
                // sync packages
                sync2Prod(DESTINATION, 'yes')

                // upload tarball
                deleteDir()
                unstash 'binary.tarball'
                sh '''
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no results/tarball/*.tar.* jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/pmm/
                '''
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo"
                    slackSend botUser: true, channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo"
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
        }
    }
}
