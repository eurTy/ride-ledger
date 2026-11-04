import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { deflateSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { client, join, state } from '../tests/support/web-client.mjs';
import { fixture, expectedSummary } from '../tests/support/scenario.mjs';

const root = fileURLToPath(new URL('./', import.meta.url));
const project = path.join(root, 'RideLedger.xcodeproj');
await mkdir(path.join(root, 'generated', 'Web'), { recursive: true });
await mkdir(path.join(project, 'xcshareddata', 'xcschemes'), { recursive: true });
for (const file of ['index.html', 'app.js', 'styles.css'])
  await copyFile(new URL('../web/' + file, import.meta.url), path.join(root, 'generated', 'Web', file));

// Only synthetic ledger data is included in the test bundle.
const { t, events } = fixture();
const source = client(state([t], events));
const bundle = await source.api.encryptedBundle(t);
const receiver = client(state([]));
join(receiver, source.api.inviteCode(t));
await writeFile(path.join(root, 'generated', 'interop.json'), JSON.stringify({
  trip: t, invite: source.api.inviteCode(t), bundle, token: await source.api.tripToken(t), expected: expectedSummary,
  secondState: receiver.stored()
}));

// An opaque 1024px app icon, generated from the project's arrow mark without extra tools.
const size = 1024, rows = Buffer.alloc(size * (size * 3 + 1));
const distance = (x, y, a, b, c, d) => {
  const t = Math.max(0, Math.min(1, ((x - a) * (c - a) + (y - b) * (d - b)) / ((c - a) ** 2 + (d - b) ** 2)));
  return Math.hypot(x - a - t * (c - a), y - b - t * (d - b));
};
for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
  const px = (x + .5) * 256 / size, py = (y + .5) * 256 / size;
  const white = Math.min(distance(px, py, 76, 180, 180, 76), distance(px, py, 112, 76, 180, 76),
    distance(px, py, 180, 76, 180, 144)) <= 10;
  const offset = y * (size * 3 + 1) + x * 3 + 1;
  rows[offset] = white ? 255 : 33; rows[offset + 1] = white ? 255 : 132; rows[offset + 2] = white ? 255 : 91;
}
const crc32 = bytes => {
  let crc = 0xffffffff;
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = crc >>> 1 ^ ((crc & 1) ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
};
const chunk = (name, bytes) => {
  const type = Buffer.from(name), length = Buffer.alloc(4), crc = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length); crc.writeUInt32BE(crc32(Buffer.concat([type, bytes])));
  return Buffer.concat([length, type, bytes, crc]);
};
const header = Buffer.alloc(13); header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 2;
const iconDirectory = path.join(root, 'generated', 'Assets.xcassets', 'AppIcon.appiconset');
await mkdir(iconDirectory, { recursive: true });
await writeFile(path.join(iconDirectory, 'AppIcon.png'), Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),
  chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]));
await writeFile(path.join(iconDirectory, 'Contents.json'), JSON.stringify({
  images: [{ filename: 'AppIcon.png', idiom: 'universal', platform: 'ios', size: '1024x1024' }],
  info: { author: 'xcode', version: 1 }
}));

let counter = 0;
const id = () => (++counter).toString(16).toUpperCase().padStart(24, '0');
const objects = new Map();
const add = (isa, rest) => { const value = id(); objects.set(value, `{ isa = ${isa}; ${rest} };`); return value; };
const quote = value => JSON.stringify(value);
const list = values => values.length ? '(' + values.map(quote).join(',') + ',)' : '()';
const file = (name, type) => add('PBXFileReference', `lastKnownFileType = ${type}; path = ${quote(name)}; sourceTree = "<group>";`);
const appSources = ['LocalLedger.swift', 'LocalWebServer.swift', 'LanSync.swift', 'IOSController.swift', 'RideLedgerApp.swift']
  .map(name => file('RideLedger/' + name, 'sourcecode.swift'));
const testSources = [file('RideLedgerTests/NativeTests.swift', 'sourcecode.swift')];
const resources = [file('generated/Web', 'folder'), file('generated/Assets.xcassets', 'folder.assetcatalog'),
  file('RideLedger/PrivacyInfo.xcprivacy', 'text.xml')];
const fixtures = [file('generated/interop.json', 'text.json')];
const appProduct = add('PBXFileReference', 'explicitFileType = wrapper.application; path = RideLedger.app; sourceTree = BUILT_PRODUCTS_DIR;');
const testProduct = add('PBXFileReference', 'explicitFileType = wrapper.cfbundle; path = RideLedgerTests.xctest; sourceTree = BUILT_PRODUCTS_DIR;');
const phase = (isa, files) => add(isa, 'buildActionMask = 2147483647; files = ' + list(files.map(ref =>
  add('PBXBuildFile', `fileRef = ${ref};`))) + '; runOnlyForDeploymentPostprocessing = 0;');
const appPhases = [phase('PBXSourcesBuildPhase', appSources), phase('PBXFrameworksBuildPhase', []), phase('PBXResourcesBuildPhase', resources)];
const testPhases = [phase('PBXSourcesBuildPhase', testSources), phase('PBXFrameworksBuildPhase', []), phase('PBXResourcesBuildPhase', fixtures)];
const configList = (debug, release) => {
  const configs = [add('XCBuildConfiguration', `name = Debug; buildSettings = { ${debug} };`),
    add('XCBuildConfiguration', `name = Release; buildSettings = { ${release} };`)];
  return add('XCConfigurationList', `buildConfigurations = ${list(configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;`);
};
const common = 'SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 16.0; SWIFT_VERSION = 5.0; CLANG_ENABLE_MODULES = YES; ENABLE_USER_SCRIPT_SANDBOXING = YES;';
const projectConfigs = configList(common + ' DEBUG_INFORMATION_FORMAT = dwarf; SWIFT_OPTIMIZATION_LEVEL = "-Onone";',
  common + ' DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"; SWIFT_COMPILATION_MODE = wholemodule;');
const appSettings = 'PRODUCT_NAME = RideLedger; PRODUCT_BUNDLE_IDENTIFIER = com.eurty.rideledger; INFOPLIST_FILE = RideLedger/Info.plist; ' +
  'MARKETING_VERSION = 0.1.4; CURRENT_PROJECT_VERSION = 1; TARGETED_DEVICE_FAMILY = 1; CODE_SIGN_STYLE = Automatic; ' +
  'ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO; ' +
  'SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO;';
const appConfigs = configList(appSettings + ' ENABLE_TESTABILITY = YES; SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;', appSettings);
const appTarget = add('PBXNativeTarget', `name = RideLedger; productName = RideLedger; productReference = ${appProduct}; ` +
  `productType = "com.apple.product-type.application"; buildConfigurationList = ${appConfigs}; buildPhases = ${list(appPhases)}; buildRules = (); dependencies = ();`);
const testSettings = 'PRODUCT_NAME = RideLedgerTests; PRODUCT_BUNDLE_IDENTIFIER = com.eurty.rideledger.tests; GENERATE_INFOPLIST_FILE = YES; ' +
  'TEST_HOST = "$(BUILT_PRODUCTS_DIR)/RideLedger.app/RideLedger"; BUNDLE_LOADER = "$(TEST_HOST)"; TARGETED_DEVICE_FAMILY = 1; CODE_SIGN_STYLE = Automatic;';
const testConfigs = configList(testSettings, testSettings);
const dependency = add('PBXTargetDependency', `target = ${appTarget};`);
const testTarget = add('PBXNativeTarget', `name = RideLedgerTests; productName = RideLedgerTests; productReference = ${testProduct}; ` +
  `productType = "com.apple.product-type.bundle.unit-test"; buildConfigurationList = ${testConfigs}; buildPhases = ${list(testPhases)}; buildRules = (); dependencies = ${list([dependency])};`);
const products = add('PBXGroup', `children = ${list([appProduct, testProduct])}; name = Products; sourceTree = "<group>";`);
const mainGroup = add('PBXGroup', `children = ${list([...appSources, ...testSources, ...resources, ...fixtures, products])}; sourceTree = "<group>";`);
const rootProject = add('PBXProject', `attributes = { LastUpgradeCheck = 2600; BuildIndependentTargetsInParallel = YES; }; ` +
  `buildConfigurationList = ${projectConfigs}; compatibilityVersion = "Xcode 14.0"; developmentRegion = "zh-Hans"; ` +
  `knownRegions = ("zh-Hans", en, Base); mainGroup = ${mainGroup}; productRefGroup = ${products}; projectDirPath = ""; projectRoot = ""; targets = ${list([appTarget, testTarget])};`);
await writeFile(path.join(project, 'project.pbxproj'), '// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56;\n objects = {\n' +
  [...objects].map(([key, value]) => key + ' = ' + value).join('\n') + `\n }; rootObject = ${rootProject};\n}\n`);
const buildable = (target, name) => `<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${target}" BuildableName="${name}" BlueprintName="${name.replace(/\.(app|xctest)$/,'')}" ReferencedContainer="container:RideLedger.xcodeproj"/>`;
await writeFile(path.join(project, 'xcshareddata', 'xcschemes', 'RideLedger.xcscheme'), `<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">${buildable(appTarget,'RideLedger.app')}</BuildActionEntry>
</BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>
<TestableReference skipped="NO" parallelizable="NO">${buildable(testTarget,'RideLedgerTests.xctest')}</TestableReference>
</Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">${buildable(appTarget,'RideLedger.app')}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">${buildable(appTarget,'RideLedger.app')}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>`);
console.log('Prepared iOS project, bundled web assets, opaque app icon and synthetic interoperability fixture.');
