package com.example

def login(String apiURL, String credentialsId) {
    withCredentials([string(credentialsId: credentialsId, variable: 'authToken')]) {
        sh """
            set +x
            oc login --token=${authToken} ${apiURL} --insecure-skip-tls-verify >/dev/null 2>&1 || echo 'OpenShift login failed'
        """
    }
}

/**
   * Runs mvn clean package on application code
   */
def unitTestAndPackageJar(String mavenCredentialsId, String pomPath, String mavenArgs) {
    stage('Unit Test & Package Jar'){
        print "Packaging Jar..."
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: mavenCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
            sh """
                mvn -f ${pomPath} ${mavenArgs} -U -DartifactoryUser=${user} -DartifactoryPassword=${pass} clean package
            """
        }
    }
}

def processTemplateAndStartBuild(String ocpUrl, String ocpAuthTokenCredentialId, String templatePath,
    String parameters, String project, String buildConfigName, String jarPath) {

    stage("OCP Build"){
        print "Building in OpenShift..."
        login(ocpUrl, ocpAuthTokenCredentialId)
        sh """
            oc process -f ${templatePath} ${parameters} -n ${project} | oc apply -f - -n ${project}
            oc start-build ${buildConfigName} --from-file=${jarPath} --follow -n ${project}
        """
    }
}

def sonar(String mavenCredentialsId, String pomPath, String mavenArgs, String profile,
    String branch, String sonarUrl, String sonarCredentialsId, boolean breakBuild){

    stage('Sonar'){
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: mavenCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
            sh """
                mvn -f ${pomPath} ${mavenArgs} \
                    -DartifactoryUser=${user} -DartifactoryPassword=${pass} \
                    clean install sonar:sonar -DskipITs -Psonar,artifactory,metrics \
                    -Dsonar.profile="${profile}" -Dsonar.branch="${branch}"
            """
        }

        pom = readMavenPom file: pomPath
        projectKey = "${pom.groupId}:${pom.artifactId}:${branch}"
        qualityGateStatus = processSonarQubeResults(sonarUrl, projectKey, sonarCredentialsId)

        if (!qualityGateStatus.contains("OK")) {
            if (breakBuild) {
                error("SonarQube quality gates failed")
            } else {
                print "Setting build status to unstable due SonarQube Quality Gate: ${qualityGateStatus}"
                currentBuild.result='UNSTABLE'
            }
        }
    }
}

/**
 * Parses logs for the SonarQube Urls, polls for sonar processing to finish
 * and returns Quality Gate Status [OK, WARN, ERROR, NONE]
 * Should be called directly after sonar stage
 */
def processSonarQubeResults(String sonarUrl, String projectKey, String sonarCredentialsId) {

    String sonarQubeTaskURL = null;
    String reportURL = null;

    // Get the most recent 150 log lines in a list
    def list = currentBuild.rawBuild.getLog(200)

    String taskLinePrefix = "[INFO] More about the report processing at "
    String reportLinePrefix = "[INFO] ANALYSIS SUCCESSFUL, you can browse "

    for (int i=list.size()-1; i>0; i--) {
        String line = list[i]
        if (line.contains(taskLinePrefix)) {
            sonarQubeTaskURL = line.substring(taskLinePrefix.length())
        }
        if (line.contains(reportLinePrefix)) {
            reportURL = line.substring(reportLinePrefix.length())
        }
    }

    if (sonarQubeTaskURL == null) {
        print "sonarQubeTaskURL url not found"
        return
    }

    print "SonarQube analysis report can be found at: " + reportURL

    String status = getSonarQubeTaskStatus(sonarQubeTaskURL)

    while (status == "PENDING" || status == "IN_PROGRESS") {
        sleep 1
        status = getSonarQubeTaskStatus(sonarQubeTaskURL)
    }

    if (status == "FAILED" || status == "CANCELED") {
        print "SonarQube analysis failed, please see to the SonarQube analysis: ${reportURL}"
        return "NONE"
    }

    qualityGateUrl = "${sonarUrl}/api/qualitygates/project_status?projectKey=${projectKey}"
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: sonarCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
        output = sh (
            script:"""
              curl -sk -u "${user}":"${pass}" ${qualityGateUrl}
            """,
            returnStdout: true
        )
        jsonOutput = readJSON text: output
        status = jsonOutput["projectStatus"]["status"]
        print "SonarQube status is " + status
        return status
    }
}

/**
 * Call into SonarQube and to get status of a project analysis
 * Could return PENDING, IN_PROGRESS FAILED, CANCELED, SUCCESS
 */
def getSonarQubeTaskStatus(String statusUrl) {
    print "Requesting SonarQube status at " + statusUrl

    output = sh (
        script:"""
          curl -sk ${statusUrl}
        """,
        returnStdout: true
    )

    jsonOutput = readJSON text: output
    taskStatus = jsonOutput["task"]["status"]
    print "SonarQube task status is " + taskStatus
    return taskStatus
}

def downloadDeploymentAndDeploy(String microservice, String project, String ocpUrl, String ocpAuthTokenCredentialId,
    String artifactoryCredentialsId, String distribuitionZipUrl, String artifactoryUrl, String imageTag, String additionalArgs) {

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: artifactoryCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
        print 'Downloading distribution.zip for deployment'
        sh """
            curl ${additionalArgs} --user "${user}":"${pass}" -L -o distro.zip ${distribuitionZipUrl}
            unzip -o distro.zip
        """
    }

    login(ocpUrl, ocpAuthTokenCredentialId)

    sh """
        oc apply -f openshift/config-maps/${project}/ -R -n ${project}
        oc apply -f openshift/deployment.yml -n ${project}
    """

    withCredentials([string(credentialsId: ocpAuthTokenCredentialId, variable: 'authToken')]) {
        openshiftDeploy(
            depCfg: microservice,
            namespace: project,
            apiURL: ocpUrl,
            authToken: authToken
        )

        openshiftVerifyDeployment(
            depCfg: microservice,
            namespace: project,
            replicaCount: '1',
            apiURL: ocpUrl,
            authToken: authToken
        )
    }
}
