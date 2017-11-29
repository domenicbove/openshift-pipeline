#!/usr/bin/groovy

import com.example.PipelineUtils

/**
   * Pipeline meant to take application code and build image to artifactory and
   * deploy in release project
   * @param gitRepoUrl URL to the DSM source code
   * @param microservice DSM name
   * @param mavenCredentialsId Credential Id to user/pass that works with FIS artifactory in maven server
   */
def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Instantiate variables used in pipeline
    ocpUrl = ""
    ocpAuthTokenCredentialId = "OCP_SA_CICD_JENKINS"
    baseMavenArgs = "-s settings/maven/settings-artifactory.xml -Dmaven.repo.local=/etc/.m2"
    def pipelineUtils = new PipelineUtils()

    node('maven') {

        // Skip TLS for Openshift Jenkins Plugin
        env.SKIP_TLS = 'true'

        stage('Checkout Source Code'){
            // Checkout source code
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'app-root']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: config.gitRepoUrl]]])
            // Checkout settings file
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mplat-openshift']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'a16fe3a9-b41b-4fe8-8f1a-55ae9607ad40', url: '']]])
        }

        pipelineUtils.unitTestAndPackageJar(config.mavenCredentialsId, "app-root/pom.xml", baseMavenArgs)

        // User build config template out of mplat-openshift
        pipelineUtils.processTemplateAndStartBuild(ocpUrl, ocpAuthTokenCredentialId,
            "templates/mplat-dsm-base-buildconfig-template.yml",
            "APPLICATION_NAME=${config.microservice}", "cicd", config.microservice, "app-root/target/application.jar")

        stage("OCP Deploy") {

            sh """
                oc process -f app-root/openshift/template.yml APPLICATION_NAME=${config.microservice} \
                    -n release | oc apply -f - -n release
            """

            withCredentials([string(credentialsId: ocpAuthTokenCredentialId, variable: 'authToken')]) {
                openshiftDeploy(
                    depCfg: config.microservice,
                    namespace: "release",
                    apiURL: ocpUrl,
                    authToken: authToken
                )

                openshiftVerifyDeployment(
                    depCfg: config.microservice,
                    namespace: "release",
                    replicaCount: '1',
                    apiURL: ocpUrl,
                    authToken: authToken
                )
            }

        }

    }

}
