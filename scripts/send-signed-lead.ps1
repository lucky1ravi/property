# Posts one signed lead to a campaign's inbound address, the way an ad platform or form tool would. For demonstrations.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\send-signed-lead.ps1                       # the pitch Facebook campaign
#   powershell -ExecutionPolicy Bypass -File .\scripts\send-signed-lead.ps1 -Campaign zillow      # the pitch Zillow campaign
#   powershell -ExecutionPolicy Bypass -File .\scripts\send-signed-lead.ps1 -Key cmp_... -Secret whsec_...   # any campaign (the secret is shown once when it is made)
#
# It makes up a person each time (so it is never filtered as a duplicate), asks about a Riverside Commons home unless you pass -ListingId, signs the exact
# body with HMAC-SHA256 and sends it in X-Signature. The new lead appears in Leads within a few seconds, tagged with the campaign.
param(
    [ValidateSet("facebook", "zillow")][string]$Campaign = "facebook",
    [string]$Key, [string]$Secret,
    [string]$Api = "http://localhost:8080",
    [string]$ListingId,
    [string]$Name, [string]$Email, [string]$Message
)
$ErrorActionPreference = "Stop"

# The two pitch campaigns have fixed demo keys (docs/LOCAL-RUN.md). Anything else needs -Key and -Secret.
if (-not $Key) {
    if ($Campaign -eq "zillow") { $Key = "cmp_pitch_zillow"; $Secret = "whsec_pitch_zillow_demo" }
    else { $Key = "cmp_pitch_facebook"; $Secret = "whsec_pitch_facebook_demo" }
}
if (-not $Secret) { throw "Give the campaign's secret with -Secret (it is shown once, when the campaign is made or its key is rotated)." }

if (-not $ListingId) {
    $homes = Invoke-RestMethod "$Api/api/leasing/public/listings?keyword=Riverside"
    # A plain list comes back as an array; a paged answer would carry the list in .rows. (.rows on an array gives an array of nulls, so test the type first.)
    $rows = @(if ($homes -is [array]) { $homes } elseif ($homes.rows) { $homes.rows })
    if ($rows.Count -eq 0) { throw "No live Riverside Commons home found. Start the stack with -Pitch, or pass -ListingId." }
    $ListingId = ($rows | Get-Random).id
}

$first = "Maya", "Jordan", "Elena", "Marcus", "Priya", "Tom", "Hannah", "Devon", "Sofia", "Liam" | Get-Random
$last = "Patel", "Brooks", "Garcia", "Webb", "Nair", "Alvarez", "Kim", "Carter", "Rossi", "Okafor" | Get-Random
if (-not $Name) { $Name = "$first $last" }
if (-not $Email) { $Email = ("{0}.{1}{2}@example.com" -f $first, $last, (Get-Random -Minimum 100 -Maximum 999)).ToLower() }
if (-not $Message) {
    $Message = @("Hi, is this home still available? I would love to tour it this weekend.",
        "Does the rent include parking? I am hoping to move in next month.",
        "We have a small dog. Is that allowed, and can we book a showing on Saturday?") | Get-Random
}

$body = @{ type = "lead"; name = $Name; email = $Email; phone = "5125550" + (Get-Random -Minimum 100 -Maximum 999); listingId = $ListingId; message = $Message } | ConvertTo-Json -Compress
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$hmac = New-Object System.Security.Cryptography.HMACSHA256 (, [System.Text.Encoding]::UTF8.GetBytes($Secret))
$signature = -join ($hmac.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") })

Write-Host "Posting a signed lead for $Name <$Email> to $Api/api/leasing/public/inbound/$Key"
try {
    $reply = Invoke-RestMethod -Method Post -Uri "$Api/api/leasing/public/inbound/$Key" -ContentType "application/json" -Headers @{ "X-Signature" = "sha256=$signature" } -Body $bytes
    Write-Host ("Accepted. Campaign: {0}. Duplicate: {1}. It is in Leads now." -f $reply.campaign, $reply.duplicate) -ForegroundColor Green
} catch {
    # Windows PowerShell keeps the server's explanation in the response stream rather than in the error record.
    $detail = $_.ErrorDetails.Message
    if (-not $detail -and $_.Exception.Response) { try { $detail = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { } }
    if ($detail) { try { $detail = ($detail | ConvertFrom-Json).detail } catch { } } else { $detail = $_.Exception.Message }
    Write-Host "Refused: $detail" -ForegroundColor Red
    exit 1
}
