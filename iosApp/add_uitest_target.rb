#!/usr/bin/env ruby
# Adds (idempotently) an `iosAppUITests` XCUITest target to iosApp.xcodeproj,
# wires it to the iosApp host, configures automatic signing, attaches the Swift
# test source, and writes a shared `iosAppUITests` scheme so `xcodebuild test`
# can run it. Re-running is safe: it removes any existing target/scheme first.
require 'xcodeproj'

PROJECT_PATH = File.join(__dir__, 'iosApp.xcodeproj')
TEST_TARGET  = 'iosAppUITests'
HOST_TARGET  = 'iosApp'
TEAM_ID      = 'S9MGTJXVTV'
DEPLOY       = '17.0'
SWIFT        = '5.0'
SRC_DIR      = 'iosAppUITests'
SRC_FILE     = File.join(SRC_DIR, 'SnapshotPBUITests.swift')

project = Xcodeproj::Project.open(PROJECT_PATH)

app = project.targets.find { |t| t.name == HOST_TARGET }
raise "Host target #{HOST_TARGET} not found" unless app

# --- Clean slate: drop any prior version of the test target / its group ---
project.targets.select { |t| t.name == TEST_TARGET }.each do |t|
  t.remove_from_project
end
if (g = project.main_group.children.find { |c| c.respond_to?(:display_name) && c.display_name == SRC_DIR })
  g.remove_from_project
end

# --- Create the UI test bundle target ---
test = project.new_target(:ui_test_bundle, TEST_TARGET, :ios, DEPLOY)

# --- Build settings on every configuration ---
test.build_configurations.each do |config|
  s = config.build_settings
  s['PRODUCT_BUNDLE_IDENTIFIER']     = 'com.snapshotpb.configuration.SnapshotPBUITests'
  s['PRODUCT_NAME']                  = '$(TARGET_NAME)'
  s['DEVELOPMENT_TEAM']              = TEAM_ID
  s['CODE_SIGN_STYLE']               = 'Automatic'
  s['IPHONEOS_DEPLOYMENT_TARGET']    = DEPLOY
  s['SWIFT_VERSION']                 = SWIFT
  s['GENERATE_INFOPLIST_FILE']       = 'YES'
  s['TEST_TARGET_NAME']              = HOST_TARGET
  s['TARGETED_DEVICE_FAMILY']        = '1,2'
  s['SWIFT_EMIT_LOC_STRINGS']        = 'NO'
  s['CODE_SIGN_IDENTITY']            = 'Apple Development'
  s['LD_RUNPATH_SEARCH_PATHS']       = ['$(inherited)', '@executable_path/Frameworks', '@loader_path/Frameworks']
end

# --- Attach the Swift test source ---
group = project.main_group.new_group(SRC_DIR, SRC_DIR)
file_ref = group.new_reference('SnapshotPBUITests.swift')
test.source_build_phase.add_file_reference(file_ref)

# --- Build order: app first, then the test bundle ---
test.add_dependency(app)

project.save

# --- Shared scheme so `xcodebuild test -scheme iosAppUITests` works ---
# Written directly as XML: this system Ruby's rexml versions conflict when the
# gem's Xcodeproj::XCScheme is loaded, so we avoid that code path entirely.
app_product  = app.product_reference.path   # SnapshotPB.app
test_product = test.product_reference.path  # iosAppUITests.xctest

def buildable_ref(uuid, product, name)
  <<~XML.chomp
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "#{uuid}"
               BuildableName = "#{product}"
               BlueprintName = "#{name}"
               ReferencedContainer = "container:iosApp.xcodeproj">
            </BuildableReference>
  XML
end

scheme_xml = <<~XML
  <?xml version="1.0" encoding="UTF-8"?>
  <Scheme
     LastUpgradeVersion = "1600"
     version = "1.7">
     <BuildAction
        parallelizeBuildables = "YES"
        buildImplicitDependencies = "YES">
        <BuildActionEntries>
           <BuildActionEntry
              buildForTesting = "YES"
              buildForRunning = "YES"
              buildForProfiling = "YES"
              buildForArchiving = "YES"
              buildForAnalyzing = "YES">
  #{buildable_ref(app.uuid, app_product, HOST_TARGET)}
           </BuildActionEntry>
           <BuildActionEntry
              buildForTesting = "YES"
              buildForRunning = "NO"
              buildForProfiling = "NO"
              buildForArchiving = "NO"
              buildForAnalyzing = "NO">
  #{buildable_ref(test.uuid, test_product, TEST_TARGET)}
           </BuildActionEntry>
        </BuildActionEntries>
     </BuildAction>
     <TestAction
        buildConfiguration = "Debug"
        selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
        selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
        shouldUseLaunchSchemeArgsEnv = "YES">
        <Testables>
           <TestableReference
              skipped = "NO">
  #{buildable_ref(test.uuid, test_product, TEST_TARGET)}
           </TestableReference>
        </Testables>
     </TestAction>
     <LaunchAction
        buildConfiguration = "Debug"
        selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
        selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
        launchStyle = "0"
        useCustomWorkingDirectory = "NO"
        ignoresPersistentStateOnLaunch = "NO"
        debugDocumentVersioning = "YES"
        debugServiceExtension = "internal"
        allowLocationSimulation = "YES">
        <BuildableProductRunnable
           runnableDebuggingMode = "0">
  #{buildable_ref(app.uuid, app_product, HOST_TARGET)}
        </BuildableProductRunnable>
     </LaunchAction>
     <ProfileAction
        buildConfiguration = "Release"
        shouldUseLaunchSchemeArgsEnv = "YES"
        savedToolIdentifier = ""
        useCustomWorkingDirectory = "NO"
        debugDocumentVersioning = "YES">
     </ProfileAction>
     <AnalyzeAction
        buildConfiguration = "Debug">
     </AnalyzeAction>
     <ArchiveAction
        buildConfiguration = "Release"
        revealArchiveInOrganizer = "YES">
     </ArchiveAction>
  </Scheme>
XML

scheme_dir = File.join(PROJECT_PATH, 'xcshareddata', 'xcschemes')
require 'fileutils'
FileUtils.mkdir_p(scheme_dir)
File.write(File.join(scheme_dir, "#{TEST_TARGET}.xcscheme"), scheme_xml)

puts "OK: created target '#{TEST_TARGET}' (host=#{HOST_TARGET}), source=#{SRC_FILE}, shared scheme '#{TEST_TARGET}'."
