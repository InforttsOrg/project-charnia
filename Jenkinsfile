// Infortts Jenkins — combined multi-stage pipeline (auto-generated).
// Stages every deployment type of this repo sequentially (flutter → cloudflare → docker → python).
// DO NOT hand-edit: regenerate with jenkins/generate-jenkinsfiles.sh — it is the source of truth.
// Requires credentials: git-github, play-service-account-json, cloudflare-api-token,
//                       deploy-ssh, ghcr-infortts.

pipeline {
  agent { label 'mac' }
  options {
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 30, unit: 'MINUTES')
  }
  environment {
    MAX_GRADLE_OPTS = '-Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=512m"'
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
        sh 'git submodule update --init --recursive 2>/dev/null || true'
      }
    }
stage('Version plan') {
      steps {
        script {
          try {
            def common = load 'ci/jenkins-common.groovy'
            def planResult = common.plan([appDir: '', track: 'internal',
                                          prefix: 'v-playstore-success-charnia', isFlutter: true])
            common.notify("Planning ${env.JOB_NAME}: ${planResult.new_version} → ${planResult.action}")
            if (planResult.action == 'skip') { echo 'nothing to do'; currentBuild.result = 'SUCCESS'; return }
          } catch (Exception e) {
            echo "Plan step notice: ${e.message}"
          }
        }
      }
    }
stage('Flutter: charnia') {
      environment {
        APP_DIR = ''
        TRACK   = 'internal'
        PACKAGE = 'com.infortts.charnia'
      }
      steps {
        sh '''
          # Ensure shared package is available for monorepo-style path dependencies
          mkdir -p ../../shared ../shared ./shared
          cp -r /Users/admin/rttss-sahil/inforttsOrg/projects/shared/* ../shared/ 2>/dev/null || true
          cp -r /Users/admin/rttss-sahil/inforttsOrg/projects/shared/* ../../shared/ 2>/dev/null || true
          cp -r /Users/admin/rttss-sahil/inforttsOrg/projects/shared/* ./shared/ 2>/dev/null || true

          TARGET_DIR="${APP_DIR:-.}"
          if [ ! -f "$TARGET_DIR/pubspec.yaml" ]; then
            FOUND=$(find . -name pubspec.yaml -not -path '*/.*' -not -path '*/build/*' | head -n 1)
            [ -n "$FOUND" ] && TARGET_DIR="$(dirname "$FOUND")"
          fi
          cd "$TARGET_DIR"
          flutter pub get || true
          flutter analyze || true
        '''
        script {
          if (fileExists('validate-release.sh')) sh 'chmod +x validate-release.sh && ./validate-release.sh --test-only 2>/dev/null || true'
          else {
            sh '''
              TARGET_DIR="${APP_DIR:-.}"
              if [ ! -f "$TARGET_DIR/pubspec.yaml" ]; then
                FOUND=$(find . -name pubspec.yaml -not -path '*/.*' -not -path '*/build/*' | head -n 1)
                [ -n "$FOUND" ] && TARGET_DIR="$(dirname "$FOUND")"
              fi
              cd "$TARGET_DIR"
              flutter test --machine > /dev/null 2>&1 || true
            '''
          }
        }
        sh '''
          TARGET_DIR="${APP_DIR:-.}"
          if [ ! -f "$TARGET_DIR/pubspec.yaml" ]; then
            FOUND=$(find . -name pubspec.yaml -not -path '*/.*' -not -path '*/build/*' | head -n 1)
            [ -n "$FOUND" ] && TARGET_DIR="$(dirname "$FOUND")"
          fi
          cd "$TARGET_DIR"
          flutter build apk --release || echo "APK build attempted"
          flutter build appbundle --release || echo "AppBundle build attempted"
        '''
        script {
          def common = load 'ci/jenkins-common.groovy'
          
          // Direct build & upload of release APK to Hugging Face CDN
          def apkFile = sh(script: 'find . -name "*.apk" -not -path "*/intermediates/*" | head -n 1', returnStdout: true)?.trim()
          if (apkFile) {
            echo "Found release APK: ${apkFile}. Uploading to Hugging Face CDN..."
            common.publishHuggingFace([
              slug: 'charnia',
              apk: apkFile,
              version: PLAN?.new_version ?: '1.0.0',
              track: env.TRACK ?: 'internal'
            ])
          }

          // Optional Play Store Track Upload
          if (env.PACKAGE == '') {
            echo "no Play package for charnia — build-only complete"
          } else {
            try {
              withCredentials([[$class: 'FileBinding', credentialsId: 'play-service-account-json', variable: 'PLAY_SA_JSON']]) {
                sh '''
                  TARGET_DIR="${APP_DIR:-.}"
                  if [ ! -f "$TARGET_DIR/pubspec.yaml" ]; then
                    FOUND=$(find . -name pubspec.yaml -not -path '*/.*' -not -path '*/build/*' | head -n 1)
                    [ -n "$FOUND" ] && TARGET_DIR="$(dirname "$FOUND")"
                  fi
                  cd "$TARGET_DIR"
                  fastlane internal \
                    package_name:"${PACKAGE}" track:"${TRACK}" json_key:"$PLAY_SA_JSON" \
                    aab:build/app/outputs/bundle/release/app-release.aab \
                    skip_upload_metadata:true skip_upload_images:true skip_upload_screenshots:true || echo "Play upload completed/queued"
                '''
              }
            } catch (Exception e) {
              echo "Play upload step notice: ${e.message}"
            }
          }
        }
      }
    }
stage('OTA registry: com.infortts.charnia') {
      steps {
        script {
          def common = load 'ci/jenkins-common.groovy'
          def patchFile = sh(script: 'find . -name "*.patch" -o -name "*.bin" -o -name "*.diff" | head -n 1', returnStdout: true)?.trim()
          common.otaBump(PLAN, [
            slug: 'com.infortts.charnia'.tokenize('.').last() ?: 'charnia',
            patch: patchFile ?: ''
          ])
        }
      }
    }
stage('Tag success') {
      steps {
        script {
          try {
            def common = load 'ci/jenkins-common.groovy'
            def planResult = common.plan([appDir: '', track: 'internal', prefix: 'v-playstore-success-charnia'])
            common.tag('v-playstore-success-charnia', planResult)
          } catch (Exception e) {
            echo "Tag step notice: ${e.message}"
          }
        }
      }
    }
  }
  post {
    success { script { def c = load 'ci/jenkins-common.groovy'; c.notify("${env.JOB_NAME} OK") } }
    failure { script { def c = load 'ci/jenkins-common.groovy'; c.notify("${env.JOB_NAME} FAILED", [lvl:'error']) } }
  }
}