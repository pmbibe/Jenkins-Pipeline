

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

def USER = ''
def SERVER = ''
def NAMESPACE = ''
def passwordResults = []

node("built-in") {
  
  stage("Convert YAML to Single Line Format") {
    try {
        def sshCommand = """
            ssh ${USER}@${SERVER} 'oc get configmap -n ${NAMESPACE} -o name | grep -v "\\-2" | cut -d"/" -f2'
        """
        def configmapList = sh(
            script: sshCommand, 
            returnStdout: true
        ).trim() 

        configmapList.split('\n').each { configM ->
            sshCommand = """
                ssh ${USER}@${SERVER} 'oc get configmap ${configM} -n ${NAMESPACE} -o yaml'
            """
            def yamlString = sh(
                script: sshCommand, 
                returnStdout: true
            ).trim()             
            def yaml = new Yaml().load(yamlString)
            
            if (yaml && yaml.data) {

                

                yaml.data.each { key, value ->
                    if (value instanceof String && value.contains('=')) {
                        // Trường hợp 2: XXX: A=B\nC=D
                        value.split('\n').findAll { line -> 
                            !line.trim().startsWith('#') && (line.contains('PASS') || line.contains('PWD'))
                        }.each { line ->
                            def parts = line.trim().split('=', 2)
                            if (parts.length == 2 && parts[1].length() >= 7) {
                                passwordResults << "${configM}.${key}: ${parts[0]}=${parts[1]}"
                            }
                        }
                    } else {
                        // Trường hợp 1: A: B
                        if ((key.contains('PASS') || key.contains('PWD')) && value.length() >= 8) {
                            passwordResults << "${configM}: ${key}: ${value}"
                        }
                    }
                }
                
            }
        }
    } catch (Exception e) {
        echo "ERROR: ${e.message}"
    }
  }

    stage("Print Password Results") {
        if (passwordResults) {
            def grouped = [:]
            passwordResults.each { result ->
                def parts = result.split(': ', 2)
                def configName = parts[0].split('\\.')[0]
                def valueStr = parts[1]
                
                if (!grouped[configName]) grouped[configName] = []
                grouped[configName] << valueStr
            }
            
            def yamlOutput = new StringBuilder("password_findings:\n")
            grouped.each { configM, values ->
                yamlOutput.append("  ${configM}:\n")
                values.each { value ->
                    yamlOutput.append("    - ${value}\n")
                }
            }
            echo yamlOutput.toString()
        } else {
            echo "password_findings: []"
        }
    }
    stage("Create Password Value YAML") {
        if (passwordResults) {
            def valueMap = [:]
            passwordResults.each { result ->
                def parts = result.split(': ', 2)
                def configName = parts[0].contains('.') ? parts[0].split('\\.')[0] : parts[0]
                def valueStr = parts[1]
                
                if (valueStr.contains('=')) {
                    // Trường hợp: configM.key: varName=varValue
                    def kvPair = valueStr.split('=', 2)
                    def varName = "${configName}.${kvPair[0]}"
                    def varValue = kvPair[1]
                    if (!valueMap[varValue]) valueMap[varValue] = []
                    valueMap[varValue] << varName
                } else {
                    // Trường hợp: configM: key: value
                    def keyValue = valueStr.split(': ', 2)
                    def varName = "${configName}.${keyValue[0]}"
                    def varValue = keyValue[1]
                    if (!valueMap[varValue]) valueMap[varValue] = []
                    valueMap[varValue] << varName
                }
            }
            
            def yamlContent = new StringBuilder("password_values:\n")
            valueMap.each { value, variables ->
                yamlContent.append("  \"${value}\":\n")
                variables.each { var ->
                    yamlContent.append("    - ${var}\n")
                }
            }
            print yamlContent.toString()
            writeFile file: 'password_values.yaml', text: yamlContent.toString()
            echo "Created password_values.yaml"
        }
    }

}
