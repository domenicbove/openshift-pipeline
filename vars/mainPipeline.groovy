#!/usr/bin/groovy

import com.example.PipelineUtils

/**
   * Main Pipeline meant to take code from source all the way
   * to production in a "true pipeline"
   * @param gitRepoUrl URL to the DSM source code
   * @param microserviceSubmodule Submodule that gets built into jar
   * @param hipchatRooms Comma delimmited hipchat rooms
   * @param microservice DSM name
   * @param mavenCredentialsId Credential Id to user/pass that works with FIS artifactory in maven server
   * @param fortifyCredentialsId Credential Id to Fortify auth token
   * @param artifactoryCredentialsId Credential Id to push/pull artifacts from artifactory
   * @param artifactoryRepoBaseURL Artifactory URL for push/pull of artifacts
   * @param artifactoryRepoName Artifactory repo name
   */
def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Instantiate variables used in pipeline
    ocpUrl = ""
    ocpAuthTokenCredentialId ="OCP_SA_CICD_JENKINS"
    capeOcpUrl = ""
    capeOcpAuthTokenCredentialId ="OCP_SA_CAPE_JENKINS"
    sonarUrl = ""
    sonarCredentialsId = '32909818-7980-4ad3-bd37-65868e55cb84'
    baseMavenArgs = "-s settings/maven/settings-artifactory.xml -Dmaven.repo.local=/etc/.m2"
    mavenArgs = ""
    gitCommit = ""
    tag = ""
    zipUrl = ""
    fortifySSCUrl = ''
    registryUrl = ''
    def pipelineUtils = new PipelineUtils()

    try {
        node('maven-fortify') {
            // Clean workspace before doing anything
            deleteDir()

            // try {
            // Skip TLS for Openshift Jenkins Plugin
            env.SKIP_TLS = 'true'

            stage('Checkout Source Code'){
                // Checkout source code
                def scmVars = checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'app-root']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: config.gitRepoUrl]]])
                gitCommit = scmVars.GIT_COMMIT.take(7)
                print "gitCommit: ${gitCommit}"
                // Checkout template files (and maven settings file)
                // TODO this settings file should be in a diff repo
                // Using tags here for the template files for versioning
                checkout([$class: 'GitSCM', branches: [[name: config.templateGitTag]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mplat-openshift']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: '']]])

                // Process the pom for correct version number
                pom = readMavenPom file: 'app-root/pom.xml'
                revision = pom.properties.revision
                // Using the base version + git commit to be the image tag as well as the version that will go on the
                // artifacts built by maven
                tag = revision + "-" + gitCommit
                mavenArgs = baseMavenArgs + " -Dchangelist= -Dsha1=-${gitCommit}"
            }

            // TODO boolean should be true for breakBuild
            // pipelineUtils.sonar(config.mavenCredentialsId, "app-root/pom.xml", mavenArgs, "Sonar way",
            //     "master", sonarUrl, sonarCredentialsId, false)
            //

            pipelineUtils.unitTestAndPackageJar(config.mavenCredentialsId, "app-root/pom.xml", mavenArgs)

            pom = readMavenPom file: "app-root/${config.microserviceSubmodule}/pom.xml"
            pipelineUtils.processTemplateAndStartBuild(ocpUrl, ocpAuthTokenCredentialId,
                "templates/mplat-dsm-base-buildconfig-template.yml",
                "APPLICATION_NAME=${config.microservice} REGISTRY_URL=${registryUrl} OUTPUT_IMAGE_TAG=${tag}",
                "cicd", config.microservice, "app-root/${config.microserviceSubmodule}/target/${pom.artifactId}-${tag}.jar")


            stage('Push OCP Objects'){

                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: config.artifactoryCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {

                    pom = readMavenPom file: "app-root/${config.microserviceSubmodule}/pom.xml"
                    zipUrl = config.artifactoryRepoBaseURL + '/' + config.artifactoryRepoName + '/' + pom.parent.groupId.replace(".", "/") + '/' + pom.artifactId + '/' + tag + '/' + pom.artifactId + '-' + tag + '.zip'
                    print "Uploading artifact to Artifactory at Url: " + zipUrl
                    // Put deployment.yml file into openshift folder and zip it up
                    sh """
                        oc process -f templates/${config.templateFileName} \
                            APPLICATION_NAME=${config.microservice} IMAGE=${registryUrl}/${config.microservice}:${tag} \
                            -o yaml -n cicd > app-root/openshift/deployment.yml

                        cd app-root
                        zip -r ../"${pom.artifactId}-${tag}-openshift-distribution.zip" openshift
                        cd ..
                        curl -X PUT --user "${user}":"${pass}" -T ${pom.artifactId}-${tag}-openshift-distribution.zip ${zipUrl}
                    """
                }
            }

        } // node

        node() {
            //TODO run on Jenkins Agent on VM, FIS to provision

            stage('Atomic Scan') {
                artifactoryDockerRepo = 'mobile-docker-1.repos.fismobile.com'
                registryCredentialsId = 'SRV_ARTIFACTORY_DOCKER_MOBILE_ARCHIVES_RW'

                print "Pulling the Docker Image"
                withCredentials([usernamePassword(credentialsId: registryCredentialsId, passwordVariable: 'password', usernameVariable: 'username')]) {
                    sh """
                        # The image has to be available to the local docker instance for atomic scan to return something other than an error 'Registries'
                        docker login --username "${username}" --password "${password}" ${artifactoryDockerRepo}
                        docker pull ${registryUrl}/${config.microservice}:${tag}
                    """
                }
                sh """
                    mkdir -p results/cve_results
                    sudo atomic scan --json ${registryUrl}/${config.microservice}:${tag} > results/cve_results/cve.json 2> results/cve_results/error.log
                    # redirecting stderr to file will result in empty file if no stderr output exists. following removes empty file -> [ -s "results/cve_results/error.log" ] || rm -f "results/cve_results/error.log"
                    [ -s "results/cve_results/error.log" ] || echo "error log is empty. removing"; rm -f "results/cve_results/error.log"
                    cat results/cve_results/cve.json
                    docker rmi ${registryUrl}/${config.microservice}:${tag}
                """
                archiveArtifacts 'results/cve_results/cve.json'
                print "Success!"
            }


        } // node
        node('maven') {

            stage('Mobile Dev Deploy in release'){
                pipelineUtils.downloadDeploymentAndDeploy(config.microservice, "release", ocpUrl, ocpAuthTokenCredentialId,
                    config.artifactoryCredentialsId, zipUrl, registryUrl, tag, '')
            }

            stage('API Tests'){
                // Reclone the source code to gain access to the api test files
                // Need to pass the gitCommit to make sure it is the same source code
                checkout([$class: 'GitSCM', branches: [[name: gitCommit]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'app-root']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: config.gitRepoUrl]]])

                // Reclone maven settings file... should be elsewhere
                checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mplat-openshift']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: 'https://bitbucket.mfoundry.net/scm/~andrew.sandstrom/mplat-openshift.git']]])

                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: config.mavenCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
                    sh """
                        mvn -f app-root/pom.xml ${mavenArgs} -DartifactoryUser=${user} -DartifactoryPassword=${pass} \
                            -Ddsm.url=https://${config.microservice}-release. verify
                    """
                }
                // Capture and aggregate JUnit reports from the API tests.
                junit '**/surefire-reports/*.xml,**/failsafe-reports/*.xml'
            }

            hipchatSend color: 'GREEN', credentialId: 'HIPCHAT_TOKEN', message: "Build Ended: ${env.JOB_NAME} ${env.BUILD_NUMBER}", room: params.hipchatRooms, sendAs: '', server: 'chat.fismobile.com', v2enabled: true

        } // node

        stage('Gate before Cape Cluster'){
            input 'Deploy to Other Cluster'
        }

        node('cape'){
            stage('Deploy'){
                pipelineUtils.downloadDeploymentAndDeploy(config.microservice, "other", capeOcpUrl, capeOcpAuthTokenCredentialId,
                    config.artifactoryCredentialsId, zipUrl, registryUrl, tag, '--proxy http://proxy.fisdev.local:8080')
            }
        }

    } catch (err) {
        currentBuild.result = 'FAILED'
        throw err
    }

}
