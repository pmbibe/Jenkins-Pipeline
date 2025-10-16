
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions
def BASTIONS = ['']
def USER = ''
def NAMESPACES= ['']
def convertKeyToInteger(key) {
    return (key instanceof String && key.isInteger()) ? key.toInteger() : key
}

def convertKeysToInteger(map) {
    def newMap = [:] // Create a new map
    map.each { k, v ->
        def newKey = (k instanceof String && k.isInteger()) ? k.toInteger() : k
        newMap[newKey] = (v instanceof Map) ? convertKeysToInteger(v) : v
    }
    return newMap
}

def findLastKeyOfEachBranch(obj, parentKey, keyList) {

    if (obj instanceof Map) {
        obj.each { key, value ->
            def currentKey = parentKey.isEmpty() ? key : "${parentKey}.${key}"
            findLastKeyOfEachBranch(value, currentKey, keyList)
        }
            
    } else {
        keyList << parentKey
    }

}

def printLastKeyOfEachBranch(yamlString) {
    def keyList = new ArrayList<>()
    def yamlParser = new Yaml().load(yamlString)
    findLastKeyOfEachBranch(yamlParser, "", keyList)
    return keyList    
}

def getValueFromKeyPath(map, keyPath) {
    def keys = keyPath.split('\\.')
    def value = map
    keys.each { key ->
        value = value[convertKeyToInteger(key)]
        
        if (value == null) {
            return null
        }
    }
    return value
}


def setValueForKeyPath(map, keyPath, newValue) {
    def keys = keyPath.split('\\.')
    def currentMap = map
    
    for (int i = 0; i < keys.size() - 1; i++) {
        def key = convertKeyToInteger(keys[i])
        if (!currentMap.containsKey(key)) {
            currentMap.put(key, [:])
        }
        currentMap = currentMap[key]
    }
    
    def lastKey = convertKeyToInteger(keys.last())
    def oldValue = currentMap[lastKey]
    
    if (oldValue instanceof List && newValue instanceof List) {
        // Kiểm tra có phần tử trùng không
        def hasCommon = oldValue.any { newValue.contains(it) }
        
        if (hasCommon) {
            echo "WARNING: Key '${keyPath}' has duplicate values. Using MERGE_UNIQUE strategy."
            // Merge và loại bỏ trùng
            def mergedList = new ArrayList(oldValue)
            newValue.each { item ->
                if (!mergedList.contains(item)) {
                    mergedList.add(item)
                }
            }
            currentMap[lastKey] = mergedList
        } else {
            // Không trùng → merge tất cả
            def mergedList = new ArrayList(oldValue)
            mergedList.addAll(newValue)
            currentMap[lastKey] = mergedList
        }
    } else if (oldValue instanceof List && !(newValue instanceof List)) {
        def mergedList = new ArrayList(oldValue)
        if (!mergedList.contains(newValue)) {
            mergedList.add(newValue)
        }
        currentMap[lastKey] = mergedList
    } else {
        currentMap[lastKey] = newValue
    }
}

def levenshteinDistance(String s1, String s2) {
    if (s2.length() == 0) return s1.length()
    if (s1.length() == 0) return s2.length()
    
    def prev = (0..s2.length()).toList()
    
    s1.eachWithIndex { char c1, int i ->
        def curr = [i + 1]
        s2.eachWithIndex { char c2, int j ->
            curr << [prev[j + 1] + 1, curr[j] + 1, prev[j] + (c1 != c2 ? 1 : 0)].min()
        }
        prev = curr
    }
    
    return prev[-1]
}
def extractKeywords(String input) {
    return input.toLowerCase().split(/[-_\s]+/).findAll { it.length() > 0 }
}

def containsAllKeywords(String candidate, List<String> keywords) {
    def lowerCandidate = candidate.toLowerCase()
    return keywords.every { keyword -> lowerCandidate.contains(keyword) }
}
def containsPriorityKeywords(String candidate) {
    def priorityKeywords = ['core', 'dbs', 'config']
    def lowerCandidate = candidate.toLowerCase()
    
    return priorityKeywords.any { keyword ->
        lowerCandidate.contains("-${keyword}") || 
        lowerCandidate.contains("_${keyword}") ||
        lowerCandidate.endsWith(keyword)
    }
}

def getBestMatch(query, candidates) {
    def bestCandidate = null
    def lowerQuery = query.toLowerCase()
    def keywords = extractKeywords(query)
    def filteredCandidates = candidates.findAll { candidate ->
        containsAllKeywords(candidate, keywords)
    }
    if (filteredCandidates.isEmpty()) {
        currentBuild.result = 'UNSTABLE'
        echo "Not Found"
    } else {
        def priorityCandidates = filteredCandidates.findAll { candidate ->
            candidate == query || containsPriorityKeywords(candidate)
        }
        def finalCandidates = priorityCandidates.isEmpty() ? filteredCandidates : priorityCandidates
        def bestDistance = Integer.MAX_VALUE
        finalCandidates.each { candidate ->
            def distance = levenshteinDistance(lowerQuery, candidate.toLowerCase())
            if (distance < bestDistance) {
                bestDistance = distance
                bestCandidate = candidate
            }
        }
    }
    
    return bestCandidate
}

def backupAndDeploy(USER, BASTION){

    def backupFile = env.WORKSPACE + "/*backup.yaml"
    def deployFile = env.WORKSPACE + "/*deploy.yaml"
    stage("Gen Backup Configmap"){
        
        sh """
                scp  ${backupFile} ${USER}@${BASTION}:/tmp/
            """ 
    }
    stage("Gen Deploy Configmap"){
        
        sh """
                scp  ${deployFile} ${USER}@${BASTION}:/tmp/
            """ 
    } 
    stage("Clean"){
        sh """
        rm -rf ${deployFile} && rm -rf ${backupFile}
        """

    }   

}

def updateConfigmap(USER, BASTION, selectedConfigmap, selectedNamespace){
    stage("Backup Configmap - ${BASTION} - ${selectedConfigmap} - ${selectedNamespace}") {
        def yamlOutput = sh(
                    script: """
                            ssh ${USER}@${BASTION} 'oc get configmap ${selectedConfigmap} -n ${selectedNamespace} -o yaml'
                            """, 
                    returnStdout: true
                ).trim()  
        def yamlString = String.valueOf(yamlOutput).trim()
        def yaml = new Yaml().load(yamlString)
        def yaml_application = new Yaml().load(yaml.data["application.yml"])
        yaml.data["application.yml"] = yaml_application
        if (yaml.metadata) {
            yaml.metadata.remove('resourceVersion')
            yaml.metadata.remove('uid')
            yaml.metadata.remove('creationTimestamp')
            yaml.metadata.remove('managedFields')
            yaml.metadata.remove('selfLink')
        }
        yaml.remove('status')
        def modifiedYamlString = new Yaml().dumpAs(yaml, null, DumperOptions.FlowStyle.BLOCK)
        env.tempFile = env.WORKSPACE + "/${BASTION}-${selectedConfigmap}-${selectedNamespace}-${BUILD_NUMBER}-configmap-backup.yaml"
        writeFile file: tempFile, text: modifiedYamlString         
        sh """
            sed -i 's/application.yml:/application.yml: |-/g' ${tempFile}
        """
                              
    }
    stage("Update Configmap - ${BASTION} - ${selectedConfigmap} - ${selectedNamespace}") {
            def yamlOutput = sh(
                    script: """
                            ssh ${USER}@${BASTION} 'oc get configmap ${selectedConfigmap} -n ${selectedNamespace} -o yaml'
                            """, 
                    returnStdout: true
                    ).trim()
            def yamlString = String.valueOf(yamlOutput).trim()
            def yaml = new Yaml().load(yamlString)
            def yaml_application = new Yaml().load(yaml.data["application.yml"])
            def yamlX = new Yaml().load(UPDATE_VARIABLES)
            def keyList = printLastKeyOfEachBranch(UPDATE_VARIABLES)
            keyList.each { key ->         
                setValueForKeyPath(yaml_application, key, getValueFromKeyPath(yamlX,key))
            }

            yaml.data["application.yml"] = yaml_application
            if (yaml.metadata) {
                yaml.metadata.remove('resourceVersion')
                yaml.metadata.remove('uid')
                yaml.metadata.remove('creationTimestamp')
                yaml.metadata.remove('managedFields')
                yaml.metadata.remove('selfLink')
            }
            yaml.remove('status')
            def modifiedYamlString = new Yaml().dumpAs(convertKeysToInteger(yaml), null, DumperOptions.FlowStyle.BLOCK)
            env.tempFile = env.WORKSPACE + "/${BASTION}-${selectedConfigmap}-${selectedNamespace}-${BUILD_NUMBER}-configmap-deploy.yaml"

            writeFile file: tempFile, text: modifiedYamlString         

            sh """
                sed -i 's/application.yml:/application.yml: |-/g' ${tempFile}
            """            
    }
    backupAndDeploy(USER, BASTION)
}

node("built-in") {

    stage("Login OCP DC2"){
        build job: "LoginOCP", 
            wait: true, 
            propagate: true       
    }
    stage("Enter module") {
            env.MODULES = input message: 'Enter module here !!!', 
                                ok: 'OK', 
                                parameters: [
                                    text(
                                    defaultValue: '', 
                                    description: 'Enter module here !!!', 
                                    name: 'MODULES', 
                                    trim: true
                                    )
                                ]                             
    }    
    stage("Enter variables") {
            env.UPDATE_VARIABLES = input message: 'Enter variables here !!!', 
                                ok: 'UPADTE', 
                                parameters: [
                                    text(
                                    defaultValue: '', 
                                    description: 'Enter variables here !!!', 
                                    name: 'UPDATE_VARIABLES', 
                                    trim: true
                                    )
                                ]                  
    }    
    def configmapsForUpdate = [:]
    def deploymentNeedsRollout = [:]
    BASTIONS.each { bastionHost ->
        configmapsForUpdate["${bastionHost}"] = {
            stage("Choose Configmap - ${bastionHost}"){
                lock(resource: 'yaml-processing', quantity: 1) {
                    NAMESPACES.each { selectedNamespace ->
                            def configmaps = sh(
                                            script: """
                                                ssh ${USER}@${bastionHost} kubectl get configmap -n ${selectedNamespace} | awk 'NR > 1 {print \$1}'
                                                    """,
                                            returnStdout: true
                                        ).trim()                                                              
                            def bestCandidate =  getBestMatch(env.MODULES, configmaps.split("\n"))    
                            echo "Best candidate: ${bestCandidate}"             
                            if (bestCandidate != null) {
                                updateConfigmap(USER, bastionHost, bestCandidate, selectedNamespace)
                                def jobB = build job: "FuzzySearchDeployment", 
                                    wait: true, 
                                    propagate: true,
                                    parameters: [
                                      string(name: 'MODULES', value: env.MODULES),
                                      string(name: 'NAMESPACE', value: selectedNamespace),
                                      string(name: 'BASTION_HOST', value: bastionHost)
                                    ]
                                def output = jobB.description      
                                deploymentNeedsRollout["${bastionHost}"] = (deploymentNeedsRollout["${bastionHost}"] ?: "") + (output ? "${output}\n" : "")   
                                                    
                            }
                    }
                }

            }
        }
    }
        parallel configmapsForUpdate  
        stage("Rollout Deployment"){
            parallel BASTIONS.collectEntries { bastionHost ->
                ["Rollout Deployment - ${bastionHost}": {
                    lock(resource: 'deployment-processing', quantity: 1) {
                        echo deploymentNeedsRollout["${bastionHost}"]
                    }
                }]
            }
        }


}
