import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions
def BASTIONS = ['']
def USER = ''
def NAMESPACES= ['', '', '']

def findConfigmapInNamespace(String user, String bastion, String namespace, 
                             String searchTerm, String moduleName) {
    /**
     * Tìm ConfigMap tương ứng với searchTerm trong namespace
     * Sử dụng getBestMatch để fuzzy search
     */
    def configmaps = sh(
        script: """
            ssh ${user}@${bastion} kubectl get configmap -n ${namespace} | awk 'NR > 1 {print \$1}'
        """,
        returnStdout: true
    ).trim()
    
    if (!configmaps) {
        echo "No configmaps found in namespace ${namespace}"
        return null
    }
    
    def bestCandidate = getBestMatch(searchTerm, configmaps.split("\n"))
    return bestCandidate
}

// ====== END NEW FUNCTIONS ======

def parseModuleConfigWithVariables(String yamlString) {
    /**
     * Parse cấu trúc mới:
     * authentication:
     *   dev:
     *     variables: {...}
     *   staging:
     *     variables: {...}
     *   all:
     *     variables: {...}
     */
    def yaml = new Yaml().load(yamlString)
    return yaml ?: [:]
}

def expandConfigmapNamespaces(def namespaceValue, List<String> definedNamespaces) {
    /**
     * Xử lý namespace value có thể là:
     * - String "dev" hoặc "All"
     * - List ["dev", "staging"]
     */
    def result = []
    
    if (namespaceValue instanceof String) {
        if (namespaceValue.trim().equalsIgnoreCase("All")) {
            result = definedNamespaces
        } else {
            if (definedNamespaces.contains(namespaceValue.trim())) {
                result = [namespaceValue.trim()]
            }
        }
    } else if (namespaceValue instanceof List) {
        namespaceValue.each { ns ->
            def trimmed = ns.toString().trim()
            if (trimmed.equalsIgnoreCase("All")) {
                result.addAll(definedNamespaces)
            } else if (definedNamespaces.contains(trimmed)) {
                result.add(trimmed)
            }
        }
        result = result.unique() // Loại bỏ duplicate
    }
    
    return result
}

// ============ THAY THẾ HÀM NÀY ============
def processModuleConfigurations(Map moduleConfigs, List<String> definedNamespaces, 
                                List<String> bastions) {
    def taskList = []
    
    moduleConfigs.each { moduleName, namespaceConfigs ->
        if (!(namespaceConfigs instanceof Map)) {
            echo "⚠️ WARNING: Module '${moduleName}' config is not a map, skipping"
            return
        }
        
        // Step 1: Lấy "all" variables
        def allVariables = [:]
        if (namespaceConfigs.containsKey('all')) {
            def allConfig = namespaceConfigs.all
            if (allConfig instanceof Map && allConfig.containsKey('variables')) {
                allVariables = allConfig.variables ?: [:]
            }
        }
        
        // Step 2: Xác định target namespaces
        def hasAllConfig = namespaceConfigs.containsKey('all')
        def specificNamespaces = namespaceConfigs.keySet().findAll { 
            it != 'all' && definedNamespaces.contains(it.toString()) 
        }
        
        // ✅ LOGIC CHÍNH: Nếu có "all" → tất cả defined namespaces phải được xử lý
        if (hasAllConfig) {
            echo "📍 Module '${moduleName}': Detected 'all' config → applying to ${definedNamespaces.size()} namespace(s): ${definedNamespaces.join(', ')}"
            
            definedNamespaces.each { targetNamespace ->
                // Lấy variables: specific (nếu có) + all (base)
                def variables = allVariables.clone()
                def configSource = 'all'
                
                if (specificNamespaces.contains(targetNamespace)) {
                    def specificConfig = namespaceConfigs[targetNamespace]
                    if (specificConfig instanceof Map && specificConfig.containsKey('variables')) {
                        variables = deepMergeMap(allVariables, specificConfig.variables ?: [:])
                        configSource = targetNamespace + " (merged with all)"
                    }
                }
                
                bastions.each { bastion ->
                    taskList.add([
                        moduleName: moduleName,
                        namespace: targetNamespace,
                        bastion: bastion,
                        variables: variables,
                        configSource: configSource
                    ])
                }
            }
        } else if (!specificNamespaces.isEmpty()) {
            // Nếu KHÔNG có "all" nhưng có namespace cụ thể → chỉ apply những namespace đó
            echo "📍 Module '${moduleName}': Applying to ${specificNamespaces.size()} specific namespace(s): ${specificNamespaces.join(', ')}"
            
            specificNamespaces.each { nsKey ->
                def nsConfig = namespaceConfigs[nsKey]
                if (!(nsConfig instanceof Map) || !nsConfig.containsKey('variables')) {
                    echo "⚠️ WARNING: Namespace '${nsKey}' has no variables, skipping"
                    return
                }
                
                bastions.each { bastion ->
                    taskList.add([
                        moduleName: moduleName,
                        namespace: nsKey.toString(),
                        bastion: bastion,
                        variables: nsConfig.variables ?: [:],
                        configSource: nsKey.toString()
                    ])
                }
            }
        } else {
            echo "⚠️ WARNING: Module '${moduleName}' has no valid namespace config (no 'all' or specific namespaces)"
        }
    }
    
    return taskList
}

// ============ THAY THẾ HÀM NÀY ============
def printAllChangesPreview(List<Map> taskList) {
    echo """

╔════════════════════════════════════════════════════════════════════╗
║                  📋 CONFIGURATION CHANGES PREVIEW                 ║
║                  Total: ${String.format('%d', taskList.size())} change(s)                            ║
╚════════════════════════════════════════════════════════════════════╝
"""
    
    def groupedByModule = taskList.groupBy { it.moduleName }
    
    groupedByModule.each { moduleName, tasks ->
        echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  🔧 MODULE: ${moduleName}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""
        
        def groupedByNamespace = tasks.groupBy { it.namespace }
        
        groupedByNamespace.each { namespace, nsTasks ->
            def firstTask = nsTasks.first()
            def configSourceInfo = firstTask.configSource
            
            echo """
  📍 Namespace: ${String.format('%-20s', namespace)} (config source: ${configSourceInfo})
     Bastions: ${nsTasks.collect { it.bastion }.unique().join(', ')}
     
"""
            
            def yamlDump = new Yaml().dumpAs(firstTask.variables, null, DumperOptions.FlowStyle.BLOCK)
            def indentedYaml = yamlDump.split('\n').collect { line -> "     ${line}" }.join('\n')
            
            echo indentedYaml
            echo "\n"
        }
    }
    
    echo """
╔════════════════════════════════════════════════════════════════════╗
║              ⚠️  Please review above before proceeding!            ║
║              Check that ALL expected namespaces are present        ║
╚════════════════════════════════════════════════════════════════════╝
"""
}

def deepMergeMap(Map base, Map override) {
    /**
     * Merge 2 maps - override values sẽ ghi đè base values
     * Nếu value là map → recursive merge
     * Nếu value khác → override lấy giá trị mới
     */
    def result = base.clone()
    
    override.each { key, value ->
        if (value instanceof Map && result.containsKey(key) && result[key] instanceof Map) {
            result[key] = deepMergeMap(result[key], value)
        } else {
            result[key] = value
        }
    }
    
    return result
}


def printChangePreview(Map task) {
    /**
     * In preview thay đổi chi tiết cho 1 task
     */
    def yamlDump = new Yaml().dumpAs(task.variables, null, DumperOptions.FlowStyle.BLOCK)
    def indentedYaml = yamlDump.split('\n').collect { line -> "  ${line}" }.join('\n')
    
    def preview = """
┌──────────────────────────────────────────────────────────────────┐
│ Module:    ${String.format('%-48s', task.moduleName)} │
│ Namespace: ${String.format('%-48s', task.namespace)} │
│ Bastion:   ${String.format('%-48s', task.bastion)} │
│ Source:    ${String.format('%-48s', task.configSource)} │
├──────────────────────────────────────────────────────────────────┤
│ VARIABLES:                                                       │
│                                                                  │
${indentedYaml.split('\n').collect { "│ " + String.format('%-60s', it) + "│" }.join('\n')}
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
"""
    
    return preview
}

def convertVariablesToUpdateFormat(Map variables) {
    /**
     * Chuyển đổi variables YAML thành format mà setValueForKeyPath có thể dùng
     * Trả về: YAML string để load vào updateConfigmap
     */
    def yaml = new Yaml()
    return yaml.dumpAs(variables, null, DumperOptions.FlowStyle.BLOCK)
}

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
                rsync -av --mkpath  ${backupFile} ${USER}@${BASTION}:/tmp/backup_${BUILD_NUMBER}/
            """ 
    }
    stage("Gen Deploy Configmap"){
        
        sh """
                rsync -av --mkpath  ${deployFile} ${USER}@${BASTION}:/tmp/deploy_${BUILD_NUMBER}/
            """ 
    } 
    stage("Clean"){
        sh """
        rm -rf ${deployFile} && rm -rf ${backupFile}
        """

    }   

}

def updateConfigmap(USER, BASTION, selectedConfigmap, selectedNamespace, Map variables = null){
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
            
            // ===== THAY ĐỔI CHÍNH: Sử dụng variables từ task thay vì UPDATE_VARIABLES =====
            if (variables != null && !variables.isEmpty()) {
                def yamlX = variables
                def keyList = printLastKeyOfEachBranch(convertVariablesToUpdateFormat(variables))
                keyList.each { key ->         
                    setValueForKeyPath(yaml_application, key, getValueFromKeyPath(yamlX, key))
                }
                echo "✓ Applied ${keyList.size()} configuration(s) to ${selectedConfigmap}"
            } else {
                echo "⚠ No variables to apply"
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

    stage("Enter Configuration") {
            env.MODULE_CONFIG = input message: 'Enter Module Configuration (YAML format)', 
                                    ok: 'PROCESS', 
                                    parameters: [
                                        text(
                                        defaultValue: '', 
                                        description: '''Example:
    authentication:
    dev:
        variables:
        server:
            port: 80
            timeout: 30
        logging:
            level: DEBUG
    all:
        variables:
        server:
            port: 8080
            timeout: 3
        logging:
            level: ERROR

    database:
    staging:
        variables:
        database:
            host: db.staging.local
            port: 5432
    all:
        variables:
        database:
            host: db.prod.local
            port: 5432

    cache:
    all:
        variables:
        redis:
            host: redis.local
            port: 6379
    ''', 
                                        name: 'MODULE_CONFIG', 
                                        trim: true
                                        )
                                    ]                  
        }

    // Parse configuration
    def moduleConfigs = parseModuleConfigWithVariables(env.MODULE_CONFIG)
    
    if (!moduleConfigs || moduleConfigs.isEmpty()) {
        echo "❌ No configuration found!"
        currentBuild.result = 'FAILED'
        return
    }
    
    echo "✓ Parsed ${moduleConfigs.size()} module(s)"
    moduleConfigs.each { moduleName, nsConfigs ->
        echo "  - ${moduleName}: ${nsConfigs.keySet().join(', ')}"
    }
    
    // Generate task list
    def taskList = processModuleConfigurations(moduleConfigs, NAMESPACES, BASTIONS)
    echo "✓ Generated ${taskList.size()} task(s)"
    
    // ===== PRINT PREVIEW TRƯỚC KHI APPLY =====
    stage("Preview Changes") {
        printAllChangesPreview(taskList)
        
        timeout(time: 30, unit: 'MINUTES') {
            input message: '✅ Proceed with deployment?', ok: 'DEPLOY'
        }
    }

    def configmapsForUpdate = [:]
    def deploymentNeedsRollout = [:]
    
    taskList.each { task ->
        def taskKey = "${task.bastion}+${task.moduleName}+${task.namespace}"
        
        configmapsForUpdate[taskKey] = {
            stage("Update ${task.moduleName} [${task.namespace}@${task.bastion}]"){
                lock(resource: 'yaml-processing', quantity: 1) {
                    // Fuzzy search ConfigMap dựa trên module name
                    def foundConfigmap = findConfigmapInNamespace(USER, task.bastion, task.namespace, 
                                                                   task.moduleName, task.moduleName)
                    
                    if (foundConfigmap) {
                        echo "✓ Found ConfigMap: ${foundConfigmap}"
                        echo "  Applying config from: ${task.configSource}"
                        
                        // Cập nhật ConfigMap với variables
                        updateConfigmap(USER, task.bastion, foundConfigmap, task.namespace, task.variables)
                        
                        // Trigger deployment rollout
                        try {
                            def jobB = build job: "FuzzySearchDeployment", 
                                wait: true, 
                                propagate: false,
                                parameters: [
                                  string(name: 'MODULES', value: task.moduleName),
                                  string(name: 'NAMESPACE', value: task.namespace),
                                  string(name: 'BASTION_HOST', value: task.bastion)
                                ]
                            def output = jobB.description      
                            deploymentNeedsRollout[taskKey] = (deploymentNeedsRollout[taskKey] ?: "") + (output ? "${output}\n" : "")
                            echo "✓ Deployment triggered"
                        } catch (Exception e) {
                            echo "⚠️ Deployment trigger failed: ${e.message}"
                            deploymentNeedsRollout[taskKey] = "FAILED"
                        }
                    } else {
                        echo "❌ ConfigMap for module '${task.moduleName}' not found in namespace '${task.namespace}'"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
    }
    if (configmapsForUpdate.isEmpty()) {
        echo "No tasks to process!"
        currentBuild.result = 'UNSTABLE'
    } else {
        parallel configmapsForUpdate
    }
stage("Deployment Summary"){
        echo """
╔════════════════════════════════════════════════════════════════════╗
║                     ✓ DEPLOYMENT SUMMARY                          ║
╚════════════════════════════════════════════════════════════════════╝
"""
        // Group theo Bastion
        def groupedByBastion = deploymentNeedsRollout.groupBy { taskKey, output ->
            taskKey.split('\\+')[0]  // Lấy bastion (phần đầu của taskKey)
        }
        
        groupedByBastion.each { bastion, results ->
            echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  🖥️  BASTION: ${bastion}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""
            
            // Group theo Module
            def groupedByModule = results.groupBy { taskKey, output ->
                def parts = taskKey.split('\\+')
                parts[1]
            }
            
            groupedByModule.each { module, moduleResults ->
                moduleResults.each { taskKey, output ->
                    def parts = taskKey.split('\\+')
                    def status = output == "FAILED" ? "❌ FAILED" : "✓ SUCCESS"
                    if (output && output != "FAILED") {
                        echo "          ${output.trim()}"
                    }
                }
            }
            
            echo ""
        }
        
        echo "╔════════════════════════════════════════════════════════════════════╗"
        echo "║                  ✓ ALL DEPLOYMENTS COMPLETED                      ║"
        echo "╚════════════════════════════════════════════════════════════════════╝"
    }
}
