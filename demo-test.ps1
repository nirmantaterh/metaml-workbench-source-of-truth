$Base = "http://localhost:8082/api/v1/wb/transmute"

$bpmnXml = @'
<?xml version="1.0" encoding="UTF-8"?><bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn"><bpmn2:process id="expenseReimbursement" name="Expense Reimbursement" isExecutable="true"><bpmn2:startEvent id="Start" /><bpmn2:serviceTask id="ServiceTask_1" name="Calculate Reimbursement" camunda:delegateExpression="${calculateReimbursementService}" /><bpmn2:userTask id="UserTask_1" name="Manager Approval"><bpmn2:extensionElements><camunda:taskListener event="complete" delegateExpression="${expenseApprovalNotifier}" /></bpmn2:extensionElements></bpmn2:userTask><bpmn2:endEvent id="End" name="Reimbursement Complete" /><bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ServiceTask_1" /><bpmn2:sequenceFlow id="f2" sourceRef="ServiceTask_1" targetRef="UserTask_1" /><bpmn2:sequenceFlow id="f3" sourceRef="UserTask_1" targetRef="End" /></bpmn2:process></bpmn2:definitions>
'@

Write-Host "=== 1. SAVE - a new model with BOTH delegate forms ==="
$saveBody = @{ name = "Expense Reimbursement"; bpmnXml = $bpmnXml } | ConvertTo-Json
$saveResponse = Invoke-RestMethod -Uri "$Base/model" -Method Post -ContentType "application/json" -Body $saveBody
$saveResponse | ConvertTo-Json -Depth 5
$modelId = $saveResponse.data.id
Write-Host ">>> model id: $modelId"

Write-Host "`n=== 2. GENERATE DELEGATES - preview both classes ==="
$modelIdBody = @{ modelId = $modelId } | ConvertTo-Json
$genResponse = Invoke-RestMethod -Uri "$Base/generate" -Method Post -ContentType "application/json" -Body $modelIdBody
$genResponse | ConvertTo-Json -Depth 5
Write-Host ">>> look for TWO delegates: calculateReimbursementService (JavaDelegate) and expenseApprovalNotifier (TaskListener)"

Write-Host "`n=== 3. GENERATE PROJECT - assemble the full Target Harness Platform ==="
$projResponse = Invoke-RestMethod -Uri "$Base/generate-project" -Method Post -ContentType "application/json" -Body $modelIdBody
$projResponse | ConvertTo-Json -Depth 5
$projectId = $projResponse.data.projectId
Write-Host ">>> project id: $projectId"

Write-Host "`n=== 4. LAUNCH - start it as its own running app (~30s) ==="
$projectIdBody = @{ projectId = $projectId } | ConvertTo-Json
$launchResponse = Invoke-RestMethod -Uri "$Base/launch-project" -Method Post -ContentType "application/json" -Body $projectIdBody
$launchResponse | ConvertTo-Json -Depth 5
$port = $launchResponse.data.port
Write-Host ">>> generated app running on port $port"

Write-Host "`n=== 5. RUNNING-PROJECTS - confirm the registry sees it ==="
Invoke-RestMethod -Uri "$Base/running-projects" -Method Get | ConvertTo-Json -Depth 5

Write-Host "`n=== 6. PROVE IT'S REAL - hit the generated app's OWN port directly ==="
Invoke-RestMethod -Uri "http://localhost:$port/api/v1/process/start" -Method Post | ConvertTo-Json -Depth 5

Write-Host "`n=== 7. STOP - tear the generated app back down ==="
Invoke-RestMethod -Uri "$Base/stop-project" -Method Post -ContentType "application/json" -Body $projectIdBody | ConvertTo-Json -Depth 5

Write-Host "`n=== 8. CONFIRM IT'S GONE - running-projects should now be empty ==="
Invoke-RestMethod -Uri "$Base/running-projects" -Method Get | ConvertTo-Json -Depth 5
