$shell = New-Object -ComObject Shell.Application
$zip = $shell.NameSpace('D:\SurvivalManager\target\survival-manager-1.0.0.jar')
$items = $zip.Items()
$hasOriginalJoml = $false
$hasShadedJoml = $false
foreach ($item in $items) {
    $n = $item.Path
    if ($n -like '*org/joml/*') { $hasOriginalJoml = $true }
    if ($n -like '*com/example/survival/ndeps/org/joml/*') { $hasShadedJoml = $true }
}
Write-Host "Original org.joml: $hasOriginalJoml"
Write-Host "Shaded joml (ndeps): $hasShadedJoml"