// Infortts Jenkins shared helpers — ported from ROOST manager/tenant pipeline version logic.
// COPIED INTO EACH PROJECT REPO AS <repo>/ci/jenkins-common.groovy (kept in-repo per decision).
// Load in a Jenkinsfile:  def common = load 'ci/jenkins-common.groovy'
def _sh(String cmd, boolean quiet=false) { sh(script: cmd, returnStdout: quiet)?.trim() ?: "" }

// Deploy-planning: tag-driven EPOCH.MAJOR.MINOR+PATCH, native-vs-logic change classification,
// per-track "playstore success" anchor tags (so logic-only changes don't bump Play base).
// opts:
//   label     : build node label        (mac | vps ...)
//   tagPrefix : 'manager-v' | 'tenant-v'| for infortts projects use the repo app dir e.g. '',
//               passed explicit: mitochondria → label 'mac', appDir 'apps/mitochondria'
//   appDir    : path to the app whose pubspec drives versions ('' for non-flutter)
//   track     : Play track (default 'internal')
//   isFlutter : true if structural detection ran against a pubspec.yaml
Map plan(Map opts = [:]) {
  def appDir      = opts.appDir ?: ''
  def track       = opts.track ?: env.TRACK ?: 'internal'
  def prefix      = opts.prefix ?: 'v-playstore-success-mitochondria'
  def isFlutter   = opts.isFlutter ?: appDir != ''

  // 1) Read version from pubspec.yaml or .version
  def fileVer = ""
  def fileBuild = 0
  if (appDir && fileExists("${appDir}/pubspec.yaml")) {
    def lines = readFile("${appDir}/pubspec.yaml").readLines()
    for (String line : lines) {
      if (line.trim().startsWith('version:')) {
        def raw = line.replace('version:', '').trim()
        def parts = raw.tokenize('+')
        fileVer = parts[0]
        fileBuild = parts.size() > 1 ? (parts[1] as int) : 0
        break
      }
    }
  }
  if (!fileVer && fileExists('.version')) {
    fileVer = readFile('.version').trim()
  }

  // 2) Most recent successful Play Store release for this track (the "anchor")
  def anchorBase = ""
  def anchorTag = _sh("git tag --list '${prefix}-${track}-*' --sort=-v:refname | head -n 1", true)
  if (anchorTag) {
    def a = anchorTag.tokenize('-').last()
    if (a != "0.0.0" && a != "") {
      anchorBase = a
    }
  }
  echo "anchor(${track})=${anchorBase} (tag ${anchorTag})"

  // 3) Latest published version from remote tags or pubspec
  def tags = _sh('git tag --list "*+[0-9]*" | sort -V | tail -n 1', true)
  def baseVer = anchorBase ?: (fileVer ?: "2.03.00"); def buildNo = fileBuild; int EPOCH=2, MAJOR=3, MINOR=0
  if (tags) {
    def v = (tags - 'v').tokenize('+')
    def tagBase = v[0]
    def tagBuild = (v.size() > 1 ? v[1] as int : 0)
    if (tagBase != "0.0.0" && tagBase != "") {
      baseVer = tagBase
      buildNo = Math.max(buildNo, tagBuild)
    }
  }
  if (baseVer && baseVer != "0.0.0") {
    def p = baseVer.tokenize('.')
    EPOCH = p[0] as int
    MAJOR = p.size() > 1 ? p[1] as int : 0
    MINOR = p.size() > 2 ? p[2] as int : 0
  }
  if (buildNo == 0) {
    buildNo = EPOCH * 10000 + MAJOR * 100 + MINOR
  }
  echo "version source: base=${baseVer} build=${buildNo} (pubspec=${fileVer}+${fileBuild}, tag=${tags})"

  // 4) Change classification vs anchor tag (or latest tag if no anchor)
  def cmpTag = anchorTag ?: tags
  def hasNativeChanges = false
  def hasLogicChanges = false
  def isMajorBump = false
  def isEpochBump = false

  if (cmpTag) {
    def changedFiles = _sh("git diff --name-only '${cmpTag}'..HEAD", true)
    def commitMsgs = _sh("git log '${cmpTag}'..HEAD --oneline", true)

    if (commitMsgs =~ /(?i)(BREAKING CHANGE|epoch:)/) {
      isEpochBump = true
    } else if (commitMsgs =~ /(?i)(major:|feat!:)/) {
      isMajorBump = true
    }

    for (String f : changedFiles.tokenize()) {
      // Ignore documentation, metadata, ci, test configs
      if (f =~ /(^|\/)(README\.md|CHANGELOG.*|VERSIONS.*|\.gitignore|\.env\.example|ci\/.*|\.github\/.*)$/) {
        continue
      }
      // Native changes: android, ios, macos, linux, windows, c++ backend, gradle, native plugins
      if (f =~ /(^|\/)(android|ios|macos|linux|windows|cpp|native|backend)\// ||
          f.endsWith('.gradle') || f.endsWith('.gradle.kts') || f.endsWith('.properties') ||
          f.endsWith('AndroidManifest.xml') || f.endsWith('Info.plist')) {
        hasNativeChanges = true
      } else if (f.endsWith('pubspec.yaml')) {
        // Check if dependencies were modified
        def depDiff = _sh("git diff '${cmpTag}'..HEAD -- '${f}' | grep -E '^\\+[ ]*(dependencies|dev_dependencies|[a-zA-Z0-9_-]+:)' | grep -vE 'version:' || true", true)
        if (depDiff) {
          hasNativeChanges = true
        }
      } else if (f =~ /(^|\/)(lib|assets|fonts|web)\// || f.endsWith('.dart')) {
        hasLogicChanges = true
      } else {
        if (appDir && f.startsWith(appDir)) {
          hasLogicChanges = true
        }
      }
    }
  } else {
    // Fresh repo or no previous tags -> initial Play Store release
    hasNativeChanges = true
  }

  // 5) Decide Action & Calculate Version
  def action = 'skip'
  def reason = 'No code changes detected'
  def newBase = baseVer
  def nextBuild = buildNo

  if (isEpochBump) {
    action = 'playstore'
    EPOCH = EPOCH + 1
    MAJOR = 0
    MINOR = 0
    reason = "Epoch breaking change explicitly specified (requires new Play Store binary)"
    newBase = "${EPOCH}.${String.format('%02d', MAJOR)}.${String.format('%02d', MINOR)}"
    nextBuild = EPOCH * 10000 + MAJOR * 100 + MINOR
  } else if (hasNativeChanges || isMajorBump || !anchorBase) {
    action = 'playstore'
    MAJOR = MAJOR + 1
    MINOR = 0
    reason = "Native / structural change detected in ${appDir} (Major bump: new Play Store binary)"
    newBase = "${EPOCH}.${String.format('%02d', MAJOR)}.${String.format('%02d', MINOR)}"
    nextBuild = EPOCH * 10000 + MAJOR * 100 + MINOR
  } else if (hasLogicChanges) {
    action = 'ota'
    MINOR = MINOR + 1
    reason = "Dart/logic-only changes (Minor OTA bump on Play Store base ${EPOCH}.${String.format('%02d', MAJOR)})"
    newBase = "${EPOCH}.${String.format('%02d', MAJOR)}.${String.format('%02d', MINOR)}"
    nextBuild = EPOCH * 10000 + MAJOR * 100 + MINOR
  }

  def newVersion = "${newBase}+${nextBuild}"
  echo "    PLAN: version=${newVersion} base=${newBase} build=${nextBuild}"
  echo "    ACTION: ${action} — ${reason}"

  return [
    new_version: newVersion, base_version: newBase, build_number: "${nextBuild}",
    action: action, reason: reason, track: track, structural: (action == 'playstore'),
  ]
}

// Tag the repo with success markers (same family as v-playstore-success-{app}-{track}-{base}).
void tag(String kind, Map p, String tokenUser='', String tokenPass='') {
  def tagName = "${kind}-${p.track}-${p.base_version}"
  def verTag = "v${p.new_version}"
  _sh("git tag -f '${tagName}' && git tag -f '${verTag}' && git push origin '${tagName}' '${verTag}' --force")
  echo "tagged ${tagName} and ${verTag}"
}

// Telegram/console notify shim.
void notify(String msg, Map opts=[:]) {
  echo "[CI] ${msg}"
  // Optional: hook to your existing telegram pipeline: curl -s -X POST https://api.telegram.org/bot<TOKEN>/sendMessage -d chat_id=<> -d text="${msg}"
}

// Hugging Face Dataset CDN publisher (APKs, OTA differential patches, manifests)
void publishHuggingFace(Map opts = [:]) {
  def slug      = opts.slug ?: opts.project ?: ''
  def apkPath   = opts.apk ?: ''
  def patchPath = opts.patch ?: ''
  def version   = opts.version ?: '1.0.0'
  def track     = opts.track ?: 'internal'
  def repo      = opts.repo ?: 'rttss/ota-patches'

  echo "[HF CDN] Publishing ${slug} to Hugging Face dataset ${repo}..."
  
  // Find upload script in ci/ or shared/
  def scriptPath = fileExists('ci/upload_to_hf.py') ? 'ci/upload_to_hf.py' : (fileExists('upload_to_hf.py') ? 'upload_to_hf.py' : '/srv/infortts-jenkins/shared/upload_to_hf.py')
  
  def cmd = "python3 ${scriptPath} --slug '${slug}' --version '${version}' --track '${track}' --repo '${repo}'"
  if (apkPath && fileExists(apkPath)) {
    cmd += " --apk '${apkPath}'"
  }
  if (patchPath && fileExists(patchPath)) {
    cmd += " --patch '${patchPath}'"
  }
  
  _sh(cmd)
}

// OTA registry bump: writes ota-release.json and pushes patch/manifest to Hugging Face CDN
void otaBump(Map p, Map extra = [:]) {
  echo "OTA bump to ${p.new_version} (base ${p.base_version}, build ${p.build_number})"
  def publishedAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX").format(new Date())
  writeFile file: 'ota-release.json', text: groovy.json.JsonOutput.toJson([
    version: p.new_version, base_version: p.base_version, latest_build: p.build_number as int,
    latest_patch: 0, published_at: publishedAt])
  
  if (extra.slug) {
    publishHuggingFace([
      slug: extra.slug,
      version: p.new_version,
      patch: extra.patch ?: '',
      apk: extra.apk ?: '',
      track: p.track ?: 'internal'
    ])
  }
}

// Rich Build Summary for Jenkins UI (displays all decision points, targets, and health checks cleanly)
void updateBuildSummary(Map p, Map extra = [:]) {
  try {
    def actionLabel = p.action ? p.action.toUpperCase() : 'BUILD'
    def versionStr = p.new_version ?: ''
    if (versionStr) {
      currentBuild.displayName = "#${env.BUILD_NUMBER} [${actionLabel}] v${versionStr}"
    }

    def androidStatus = extra.android ?: (p.action == 'playstore' ? '✅ Native .aab (Google Play internal track)' : (p.action == 'ota' ? '📦 OTA Differential Patch (HF CDN)' : (p.action == 'skip' ? '⏭️ Skipped (no native change)' : 'N/A')))
    def webStatus = extra.web ?: 'N/A'
    def backendStatus = extra.backend ?: (extra.deploy_host ? "${extra.deploy_host}" : 'None')
    def healthStatus = extra.health ?: (extra.deploy_host ? 'Pending execution' : 'N/A')
    def envDetails = extra.env ?: "Node: ${env.NODE_NAME ?: 'mac/vps'} | JDK: 17 | Flutter: 3.47.0"

    def summary = """🚀 Infortts CI/CD Decision Matrix: ${env.JOB_NAME} #${env.BUILD_NUMBER} [${actionLabel}]
• Action: ${actionLabel} — ${p.reason ?: 'Standard build'}
• Version Planning: ${p.new_version ?: 'N/A'} (Base: ${p.base_version ?: 'N/A'}, Build: ${p.build_number ?: 'N/A'})
• Android Target: ${androidStatus}
• Web / Cloudflare: ${webStatus}
• Backend Server: ${backendStatus}
• Healthcheck: ${healthStatus}
• Environment: ${envDetails}"""

    currentBuild.description = summary
  } catch (Exception e) {
    echo "Notice: Could not set build summary card: ${e.message}"
  }
}

return this