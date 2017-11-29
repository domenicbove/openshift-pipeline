#!/usr/bin/groovy

import com.example.PipelineUtils

/**
   * Pipeline meant to take feature branch code and build/deploy in
   * a short lived OpenShift project, provisioning it if necessary
   * @param gitRepoUrl URL to the DSM source code
   * @param microserviceSubmodule Submodule that gets built into jar
   * @param microservice DSM name
   * @param mavenCredentialsId Credential Id to user/pass that works with FIS artifactory in maven server
   * @param deployAMQ Boolean to deploy AMQ
   * @param deployJDG Boolean to deploy JDG
   */
def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Instantiate variables used in pipeline
    ocpUrl = ""
    ocpAuthTokenCredentialId = "OCP_SA_CICD_JENKINS"
    sonarUrl = ""
    sonarCredentialsId = '32909818-7980-4ad3-bd37-65868e55cb84'
    tag = ""
    baseMavenArgs = "-s settings/settings-artifactory.xml -Dmaven.repo.local=/etc/.m2"
    def pipelineUtils = new PipelineUtils()

    node('maven') {

        // Skip TLS for Openshift Jenkins Plugin
        env.SKIP_TLS = 'true'

        userStoryId = sh (
            script: "echo ${env.BRANCH_NAME} | tr -dc '0-9'",
            returnStdout: true
        ).trim()

        featureProject = "${config.microservice}-${userStoryId}"

        // Create new project for the feature branch if it does not exist
        String projectQuery = sh (
            script:"""
                oc get projects
            """,
            returnStdout: true
        )

        if (!projectQuery.contains(featureProject)) {
            stage ('Creating Project') {
                // To grant the jenkins serviceaccount self provisioner cluster role run:
                //$ oc adm policy add-cluster-role-to-user self-provisioner system:serviceaccount:cicd:jenkins -n cicd
                print "Creating project ${featureProject}"
                pipelineUtils.login(ocpUrl, ocpAuthTokenCredentialId)
                sh """
                    oc new-project ${featureProject}
                """

                print "Giving groups permissions to ${featureProject}"
                sh """
                    oc policy add-role-to-group admin OpenShift_Administrators -n ${featureProject}
                    oc policy add-role-to-group admin OpenShift_Developers -n ${featureProject}
                    oc policy add-role-to-group admin OpenShift_RO -n ${featureProject}
                """

                print "Updating service account permissions"
                sh """
                    oc policy add-role-to-user view system:serviceaccount:${featureProject}:default -n ${featureProject}
                    oc export secrets registry -n release | oc apply -f - -n ${featureProject}
                    oc secrets link default registry --for=pull
                """
            }
        }

        print "Copying secrets and configmaps from release into ${featureProject}"
        sh """
            oc export secrets,configmaps -l group=main -n release | oc apply -f - -n ${featureProject}
        """

        stage('Checkout Source Code'){
            // Checkout source code
            checkout([$class: 'GitSCM', branches: [[name: env.BRANCH_NAME]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'app-root']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: config.gitRepoUrl]]])
            // Checkout settings file
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mplat-openshift']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: '']]])

            pom = readMavenPom file: 'app-root/pom.xml'
            // Using the base version + git commit to be the image tag as well as the version that will go on the
            // artifacts built by maven
            tag = pom.properties.revision + pom.properties.changelist
        }


        if (config.deployBackingService) {
            stage ("Deploy ${config.backingServiceName}") {

                print "Cloning backend code..."
                checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'backing-service-root']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: config.backingServiceGitRepoUrl]]])

                // Pulls latest tag of backing-service image in artifactory
                sh """
                    oc process -f backing-service-root/openshift/template.yml APPLICATION_NAME=${config.backingServiceName} \
                        -n ${featureProject} | oc apply -f - -n ${featureProject}
                """

                withCredentials([string(credentialsId: ocpAuthTokenCredentialId, variable: 'authToken')]) {
                    openshiftDeploy(
                        depCfg: config.backingServiceName,
                        namespace: featureProject,
                        apiURL: ocpUrl,
                        authToken: authToken
                    )

                    openshiftVerifyDeployment(
                        depCfg: config.backingServiceName,
                        namespace: featureProject,
                        replicaCount: '1',
                        apiURL: ocpUrl,
                        authToken: authToken
                    )
                }
            }
        }

        // pipelineUtils.sonar(config.mavenCredentialsId, "app-root/pom.xml", baseMavenArgs,
        //     "Sonar way", "${env.BRANCH_NAME}", sonarUrl, sonarCredentialsId, false)

        pipelineUtils.unitTestAndPackageJar(config.mavenCredentialsId, "app-root/pom.xml", baseMavenArgs)

        pom = readMavenPom file: "app-root/${config.microserviceSubmodule}/pom.xml"
        pipelineUtils.processTemplateAndStartBuild(ocpUrl, ocpAuthTokenCredentialId,
            "mplat-openshift/mplat-openshift-system/openshift/templates/mplat-dsm-feature-template.yml",
            "APPLICATION_NAME=${config.microservice}", featureProject, config.microservice, "app-root/${config.microserviceSubmodule}/target/${pom.artifactId}-${tag}.jar")


        stage('OCP Deploy'){

            withCredentials([string(credentialsId: ocpAuthTokenCredentialId, variable: 'authToken')]) {
                openshiftVerifyDeployment(
                    depCfg: config.microservice,
                    namespace: featureProject,
                    replicaCount: '1',
                    apiURL: ocpUrl,
                    authToken: authToken
                )
            }

        }

        stage('API Tests'){
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: config.mavenCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
                sh """
                    mvn -f app-root/pom.xml ${baseMavenArgs} -DartifactoryUser=${user} -DartifactoryPassword=${pass} \
                        -Ddsm.url=https://${config.microservice}-${featureProject} verify
                """
            }
            // Capture and aggregate JUnit reports from the API tests.
            junit '**/surefire-reports/*.xml,**/failsafe-reports/*.xml'
        }

    }

    stage('Delete Project'){
        input 'Delete project'

        node('maven') {
            pipelineUtils.login(ocpUrl, ocpAuthTokenCredentialId)

            print "Removing groups permissions from ${featureProject}"
            sh """
                oc policy remove-role-from-group admin OpenShift_Administrators -n ${featureProject}
                oc policy remove-role-from-group admin OpenShift_Developers -n ${featureProject}
                oc policy remove-role-from-group admin OpenShift_RO -n ${featureProject}
            """

            print "Creating project ${featureProject}"
            sh """
                oc delete project ${featureProject}
            """
        } // node

    } // stage

}
