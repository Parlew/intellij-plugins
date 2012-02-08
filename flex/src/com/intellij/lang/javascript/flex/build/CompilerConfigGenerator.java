package com.intellij.lang.javascript.flex.build;

import com.intellij.javascript.flex.FlexApplicationComponent;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.javascript.flex.TargetPlayerUtils;
import com.intellij.lang.javascript.flex.flexunit.FlexUnitPrecompileTask;
import com.intellij.lang.javascript.flex.projectStructure.CompilerOptionInfo;
import com.intellij.lang.javascript.flex.projectStructure.FlexProjectLevelCompilerOptionsHolder;
import com.intellij.lang.javascript.flex.projectStructure.ValueSource;
import com.intellij.lang.javascript.flex.projectStructure.model.*;
import com.intellij.lang.javascript.flex.projectStructure.options.BCUtils;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.lang.javascript.flex.projectStructure.options.FlexProjectRootsUtil;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.lang.javascript.flex.sdk.FlexmojosSdkType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.PairConsumer;
import com.intellij.util.PathUtil;
import com.intellij.util.PlatformUtils;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CompilerConfigGenerator {

  private static final String[] LIB_ORDER =
    {"framework", "textLayout", "osmf", "spark", "sparkskins", "rpc", "charts", "spark_dmv", "osmf", "mx", "advancedgrids"};

  private final Module myModule;
  private final FlexIdeBuildConfiguration myBC;
  private final boolean myFlexUnit;
  private final Sdk mySdk;
  private final boolean myFlexmojos;
  private final CompilerOptions myModuleLevelCompilerOptions;
  private final CompilerOptions myProjectLevelCompilerOptions;

  private CompilerConfigGenerator(final @NotNull Module module,
                                  final @NotNull FlexIdeBuildConfiguration bc,
                                  final @NotNull CompilerOptions moduleLevelCompilerOptions,
                                  final @NotNull CompilerOptions projectLevelCompilerOptions) throws IOException {
    myModule = module;
    myBC = bc;
    myFlexUnit = myBC.getMainClass().equals(FlexUnitPrecompileTask.getFlexUnitLauncherName(myModule.getName()));
    mySdk = bc.getSdk();
    if (mySdk == null) {
      throw new IOException(FlexBundle.message("sdk.not.set.for.bc.0.of.module.1", bc.getName(), module.getName()));
    }
    myFlexmojos = mySdk.getSdkType() == FlexmojosSdkType.getInstance();
    myModuleLevelCompilerOptions = moduleLevelCompilerOptions;
    myProjectLevelCompilerOptions = projectLevelCompilerOptions;
  }

  public static VirtualFile getOrCreateConfigFile(final Module module, final FlexIdeBuildConfiguration bc) throws IOException {
    final CompilerConfigGenerator generator =
      new CompilerConfigGenerator(module, bc,
                                  FlexBuildConfigurationManager.getInstance(module).getModuleLevelCompilerOptions(),
                                  FlexProjectLevelCompilerOptionsHolder.getInstance(module.getProject()).getProjectLevelCompilerOptions());
    final String text = generator.generateConfigFileText();
    final String name =
      FlexCompilerHandler.generateConfigFileName(module, bc.getName(), PlatformUtils.getPlatformPrefix().toLowerCase(), null);
    return FlexCompilationUtils.getOrCreateConfigFile(module.getProject(), name, text);
  }

  private String generateConfigFileText() throws IOException {
    final Element rootElement =
      new Element(FlexCompilerConfigFileUtil.FLEX_CONFIG, FlexApplicationComponent.HTTP_WWW_ADOBE_COM_2006_FLEX_CONFIG);

    addDebugOption(rootElement);
    addMandatoryOptions(rootElement);
    handleOptionsWithSpecialValues(rootElement);
    addSourcePaths(rootElement);
    if (!myFlexmojos) {
      addNamespaces(rootElement);
      addRootsFromSdk(rootElement);
    }
    addLibs(rootElement);
    addOtherOptions(rootElement);
    addInputOutputPaths(rootElement);

    return JDOMUtil.writeElement(rootElement, "\n");
  }

  private void addDebugOption(final Element rootElement) {
    final FlexCompilerProjectConfiguration instance = FlexCompilerProjectConfiguration.getInstance(myModule.getProject());
    final boolean debug =
      myBC.getOutputType() == OutputType.Library ? instance.SWC_DEBUG_ENABLED : instance.SWF_DEBUG_ENABLED;
    addOption(rootElement, CompilerOptionInfo.DEBUG_INFO, String.valueOf(debug));
  }

  private void addMandatoryOptions(final Element rootElement) {
    addOption(rootElement, CompilerOptionInfo.WARN_NO_CONSTRUCTOR_INFO, "false");
    if (myFlexmojos) return;

    final BuildConfigurationNature nature = myBC.getNature();
    final String targetPlayer = nature.isWebPlatform()
                                ? myBC.getDependencies().getTargetPlayer()
                                : TargetPlayerUtils.getMaximumTargetPlayer(mySdk.getHomePath());
    addOption(rootElement, CompilerOptionInfo.TARGET_PLAYER_INFO, targetPlayer);

    if (StringUtil.compareVersionNumbers(mySdk.getVersionString(), "4.5") >= 0) {
      final String swfVersion = nature.isWebPlatform() ? getSwfVersionForTargetPlayer(targetPlayer)
                                                       : getSwfVersionForSdk(mySdk.getVersionString());
      addOption(rootElement, CompilerOptionInfo.SWF_VERSION_INFO, swfVersion);
    }

    if (nature.isMobilePlatform()) {
      addOption(rootElement, CompilerOptionInfo.MOBILE_INFO, "true");
      addOption(rootElement, CompilerOptionInfo.PRELOADER_INFO, "spark.preloaders.SplashScreen");
    }

    final String accessible = nature.isMobilePlatform() ? "false"
                                                        : StringUtil.compareVersionNumbers(mySdk.getVersionString(), "4") >= 0 ? "true"
                                                                                                                               : "false";
    addOption(rootElement, CompilerOptionInfo.ACCESSIBLE_INFO, accessible);

    final String fontManagers = StringUtil.compareVersionNumbers(mySdk.getVersionString(), "4") >= 0
                                ? "flash.fonts.JREFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.BatikFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.AFEFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.CFFFontManager"

                                : "flash.fonts.JREFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.AFEFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.BatikFontManager";
    addOption(rootElement, CompilerOptionInfo.FONT_MANAGERS_INFO, fontManagers);

    addOption(rootElement, CompilerOptionInfo.STATIC_RSLS_INFO, "false");
  }

  private static String getSwfVersionForTargetPlayer(final String targetPlayer) {
    if (StringUtil.compareVersionNumbers(targetPlayer, "11.1") >= 0) return "14";
    if (StringUtil.compareVersionNumbers(targetPlayer, "11") >= 0) return "13";
    if (StringUtil.compareVersionNumbers(targetPlayer, "10.3") >= 0) return "12";
    if (StringUtil.compareVersionNumbers(targetPlayer, "10.2") >= 0) return "11";
    if (StringUtil.compareVersionNumbers(targetPlayer, "10.1") >= 0) return "10";
    return "9";
  }

  private static String getSwfVersionForSdk(final String sdkVersion) {
    if (StringUtil.compareVersionNumbers(sdkVersion, "4.6") >= 0) return "14";
    if (StringUtil.compareVersionNumbers(sdkVersion, "4.5") >= 0) return "11";
    assert false;
    return null;
  }

  /**
   * Adds options that get incorrect default values inside compiler code if not set explicitly.
   */
  private void handleOptionsWithSpecialValues(final Element rootElement) {
    for (final CompilerOptionInfo info : CompilerOptionInfo.getOptionsWithSpecialValues()) {
      final Pair<String, ValueSource> valueAndSource = getValueAndSource(info);

      if (myFlexmojos && valueAndSource.second == ValueSource.GlobalDefault) continue;

      final boolean themeForPureAS = myBC.isPureAs() && "compiler.theme".equals(info.ID);
      if (valueAndSource.second == ValueSource.GlobalDefault && (!valueAndSource.first.isEmpty() || themeForPureAS)) {
        // do not add empty preloader to Web/Desktop, let compiler take default itself (mx.preloaders.SparkDownloadProgressBar when -compatibility-version >= 4.0 and mx.preloaders.DownloadProgressBar when -compatibility-version < 4.0)
        addOption(rootElement, info, valueAndSource.first);
      }
    }
  }

  private void addNamespaces(final Element rootElement) {
    final StringBuilder namespaceBuilder = new StringBuilder();
    FlexSdkUtils.processStandardNamespaces(myBC, new PairConsumer<String, String>() {
      public void consume(final String namespace, final String relativePath) {
        if (namespaceBuilder.length() > 0) {
          namespaceBuilder.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
        }
        namespaceBuilder.append(namespace).append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append("${FLEX_SDK}/").append(relativePath);
      }
    });

    if (namespaceBuilder.length() == 0) return;
    final CompilerOptionInfo info = CompilerOptionInfo.getOptionInfo("compiler.namespaces.namespace");
    addOption(rootElement, info, namespaceBuilder.toString());
  }

  private void addRootsFromSdk(final Element rootElement) {
    final CompilerOptionInfo localeInfo = CompilerOptionInfo.getOptionInfo("compiler.locale");
    if (!getValueAndSource(localeInfo).first.isEmpty()) {
      addOption(rootElement, CompilerOptionInfo.LIBRARY_PATH_INFO, mySdk.getHomePath() + "/frameworks/locale/{locale}");
    }

    final Map<String, String> libNameToRslInfo = new THashMap<String, String>();

    for (final String swcUrl : mySdk.getRootProvider().getUrls(OrderRootType.CLASSES)) {
      final String swcPath = VirtualFileManager.extractPath(StringUtil.trimEnd(swcUrl, JarFileSystem.JAR_SEPARATOR));
      LinkageType linkageType = BCUtils.getSdkEntryLinkageType(swcPath, myBC);

      // check applicability
      if (linkageType == null) continue;
      // resolve default
      if (linkageType == LinkageType.Default) linkageType = myBC.getDependencies().getFrameworkLinkage();
      if (linkageType == LinkageType.Default) linkageType = BCUtils.getDefaultFrameworkLinkage(mySdk.getVersionString(), myBC.getNature());

      final CompilerOptionInfo info = linkageType == LinkageType.Merged ? CompilerOptionInfo.LIBRARY_PATH_INFO :
                                      linkageType == LinkageType.RSL ? CompilerOptionInfo.LIBRARY_PATH_INFO :
                                      linkageType == LinkageType.External ? CompilerOptionInfo.EXTERNAL_LIBRARY_INFO :
                                      linkageType == LinkageType.Include ? CompilerOptionInfo.INCLUDE_LIBRARY_INFO :
                                      null;

      assert info != null : swcPath + ": " + linkageType.getShortText();

      addOption(rootElement, info, swcPath);

      if (linkageType == LinkageType.RSL) {
        final String swcName = PathUtil.getFileName(swcPath);
        assert swcName.endsWith(".swc") : swcUrl;
        final String libName = swcName.substring(0, swcName.length() - ".swc".length());

        final String swzVersion = libName.equals("textLayout")
                                  ? getTextLayoutSwzVersion(mySdk.getVersionString())
                                  : libName.equals("osmf")
                                    ? getOsmfSwzVersion(mySdk.getVersionString())
                                    : mySdk.getVersionString();
        final String swzUrl;
        swzUrl = libName.equals("textLayout")
                 ? "http://fpdownload.adobe.com/pub/swz/tlf/" + swzVersion + "/textLayout_" + swzVersion + ".swz"
                 : "http://fpdownload.adobe.com/pub/swz/flex/" + mySdk.getVersionString() + "/" + libName + "_" + swzVersion + ".swz";

        final StringBuilder rslBuilder = new StringBuilder();
        rslBuilder
          .append(swcPath)
          .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append(swzUrl)
          .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append("http://fpdownload.adobe.com/pub/swz/crossdomain.xml")
          .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append(libName).append('_').append(swzVersion).append(".swz")
          .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append(""); // no failover policy file url

        libNameToRslInfo.put(libName, rslBuilder.toString());
      }
    }

    addRslInfo(rootElement, libNameToRslInfo);
  }

  private void addRslInfo(final Element rootElement, final Map<String, String> libNameToRslInfo) {
    if (libNameToRslInfo.isEmpty()) return;

    // RSL order is important!
    for (final String libName : LIB_ORDER) {
      final String rslInfo = libNameToRslInfo.remove(libName);
      if (rslInfo != null) {
        addOption(rootElement, CompilerOptionInfo.RSL_TWO_URLS_PATH_INFO, rslInfo);
      }
    }

    // now add other in random order, though up to Flex SDK 4.5.1 the map should be empty at this stage
    for (final String rslInfo : libNameToRslInfo.values()) {
      addOption(rootElement, CompilerOptionInfo.RSL_TWO_URLS_PATH_INFO, rslInfo);
    }
  }

  private static String getTextLayoutSwzVersion(final String sdkVersion) {
    return sdkVersion.startsWith("4.0")
           ? "textLayout_1.0.0.595"
           : sdkVersion.startsWith("4.1")
             ? "1.1.0.604"
             : "2.0.0.232";
  }

  private static String getOsmfSwzVersion(final String sdkVersion) {
    return StringUtil.compareVersionNumbers(sdkVersion, "4.5") < 0 ? "4.0.0.13495" : "1.0.0.16316";
  }

  private void addLibs(final Element rootElement) {
    for (final DependencyEntry entry : myBC.getDependencies().getEntries()) {
      final LinkageType linkageType = entry.getDependencyType().getLinkageType();

      if (entry instanceof BuildConfigurationEntry) {
        if (linkageType == LinkageType.LoadInRuntime) continue;

        final FlexIdeBuildConfiguration bc = ((BuildConfigurationEntry)entry).findBuildConfiguration();
        if (bc != null) {
          addLib(rootElement, bc.getOutputFilePath(true), linkageType);
        }
      }
      else if (entry instanceof ModuleLibraryEntry) {
        final LibraryOrderEntry orderEntry =
          FlexProjectRootsUtil.findOrderEntry((ModuleLibraryEntry)entry, ModuleRootManager.getInstance(myModule));
        if (orderEntry != null) {
          addLibraryRoots(rootElement, orderEntry.getRootFiles(OrderRootType.CLASSES), linkageType);
        }
      }
      else if (entry instanceof SharedLibraryEntry) {
        final Library library = FlexProjectRootsUtil.findOrderEntry(myModule.getProject(), (SharedLibraryEntry)entry);
        if (library != null) {
          addLibraryRoots(rootElement, library.getFiles((OrderRootType.CLASSES)), linkageType);
        }
      }
    }
  }

  private void addLibraryRoots(final Element rootElement, final VirtualFile[] libClassRoots, final LinkageType linkageType) {
    for (VirtualFile libFile : libClassRoots) {
      libFile = FlexCompilerHandler.getRealFile(libFile);

      if (libFile != null && libFile.isDirectory()) {
        addOption(rootElement, CompilerOptionInfo.SOURCE_PATH_INFO, libFile.getPath());
      }
      else if (libFile != null && !libFile.isDirectory() && "swc".equalsIgnoreCase(libFile.getExtension())) {
        // "airglobal.swc" and "playerglobal.swc" file names are hardcoded in Flex compiler
        // including libraries like "playerglobal-3.5.0.12683-9.swc" may lead to error at runtime like "VerifyError Error #1079: Native methods are not allowed in loaded code."
        // so here we just skip including such libraries in config file.
        // Compilation should be ok because base flexmojos config file contains correct reference to its copy in target/classes/libraries/playerglobal.swc
        final String libFileName = libFile.getName().toLowerCase();
        if (libFileName.startsWith("airglobal") && !libFileName.equals("airglobal.swc") ||
            libFileName.startsWith("playerglobal") && !libFileName.equals("playerglobal.swc")) {
          continue;
        }

        addLib(rootElement, libFile.getPath(), linkageType);
      }
    }
  }

  private void addLib(final Element rootElement, final String swcPath, final LinkageType linkageType) {
    final CompilerOptionInfo info = linkageType == LinkageType.Merged || linkageType == LinkageType.RSL
                                    ? CompilerOptionInfo.LIBRARY_PATH_INFO
                                    : linkageType == LinkageType.External
                                      ? CompilerOptionInfo.EXTERNAL_LIBRARY_INFO
                                      : linkageType == LinkageType.Include
                                        ? CompilerOptionInfo.INCLUDE_LIBRARY_INFO
                                        : null;
    assert info != null : swcPath + ": " + linkageType;

    addOption(rootElement, info, swcPath);

    if (linkageType == LinkageType.RSL) {
      // todo add RSL URLs
    }
  }

  private void addSourcePaths(final Element rootElement) {
    final String localeValue = getValueAndSource(CompilerOptionInfo.getOptionInfo("compiler.locale")).first;
    final List<String> locales = StringUtil.split(localeValue, String.valueOf(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR));
    // when adding source paths we respect locales set both in UI and in Additional compiler options
    locales.addAll(FlexUtils.getOptionValues(myProjectLevelCompilerOptions.getAdditionalOptions(), "locale", "compiler.locale"));
    locales.addAll(FlexUtils.getOptionValues(myModuleLevelCompilerOptions.getAdditionalOptions(), "locale", "compiler.locale"));
    locales.addAll(FlexUtils.getOptionValues(myBC.getCompilerOptions().getAdditionalOptions(), "locale", "compiler.locale"));

    final Set<String> sourcePathsWithLocaleToken = new THashSet<String>(); // Set - to avoid duplication of paths like "locale/{locale}"
    final List<String> sourcePathsWithoutLocaleToken = new LinkedList<String>();

    for (final VirtualFile sourceRoot : ModuleRootManager.getInstance(myModule).getSourceRoots(myFlexUnit)) {
      if (locales.contains(sourceRoot.getName())) {
        sourcePathsWithLocaleToken.add(sourceRoot.getParent().getPath() + "/" + FlexCompilerHandler.LOCALE_TOKEN);
      }
      else {
        sourcePathsWithoutLocaleToken.add(sourceRoot.getPath());
      }
    }

    final StringBuilder sourcePathBuilder = new StringBuilder();

    for (final String sourcePath : sourcePathsWithLocaleToken) {
      if (sourcePathBuilder.length() > 0) {
        sourcePathBuilder.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
      }
      sourcePathBuilder.append(sourcePath);
    }

    for (final String sourcePath : sourcePathsWithoutLocaleToken) {
      if (sourcePathBuilder.length() > 0) {
        sourcePathBuilder.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
      }
      sourcePathBuilder.append(sourcePath);
    }

    addOption(rootElement, CompilerOptionInfo.SOURCE_PATH_INFO, sourcePathBuilder.toString());
  }

  private void addOtherOptions(final Element rootElement) {
    final Map<String, String> options = new THashMap<String, String>(myProjectLevelCompilerOptions.getAllOptions());
    options.putAll(myModuleLevelCompilerOptions.getAllOptions());
    options.putAll(myBC.getCompilerOptions().getAllOptions());

    final String addOptions = myProjectLevelCompilerOptions.getAdditionalOptions() + " " +
                              myModuleLevelCompilerOptions.getAdditionalOptions() + " " +
                              myBC.getCompilerOptions().getAdditionalOptions();
    final List<String> contextRootInAddOptions = FlexUtils.getOptionValues(addOptions, "context-root", "compiler.context-root");

    if (options.get("compiler.context-root") == null && contextRootInAddOptions.isEmpty()) {
      final List<String> servicesInAddOptions = FlexUtils.getOptionValues(addOptions, "services", "compiler.services");
      if (options.get("compiler.services") != null || !servicesInAddOptions.isEmpty()) {
        options.put("compiler.context-root", "");
      }
    }

    for (final Map.Entry<String, String> entry : options.entrySet()) {
      addOption(rootElement, CompilerOptionInfo.getOptionInfo(entry.getKey()), entry.getValue());
    }

    final String namespacesRaw = options.get("compiler.namespaces.namespace");
    if (namespacesRaw != null && myBC.getOutputType() == OutputType.Library) {
      final String namespaces = FlexUtils.replacePathMacros(namespacesRaw, myModule,
                                                            myFlexmojos ? "" : mySdk.getHomePath());
      final StringBuilder buf = new StringBuilder();
      for (final String listEntry : StringUtil.split(namespaces, String.valueOf(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR))) {
        final int tabIndex = listEntry.indexOf(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR);
        assert tabIndex != -1 : namespaces;
        final String namespace = listEntry.substring(0, tabIndex);
        if (buf.length() > 0) buf.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
        buf.append(namespace);
      }

      if (buf.length() > 0) {
        addOption(rootElement, CompilerOptionInfo.INCLUDE_NAMESPACES_INFO, buf.toString());
      }
    }
  }

  private void addInputOutputPaths(final Element rootElement) throws IOException {
    if (myBC.getOutputType() == OutputType.Library) {
      if (myFlexmojos) return;

      final Ref<Boolean> noClasses = new Ref<Boolean>(true);
      final ProjectFileIndex projectFileIndex = ProjectRootManager.getInstance(myModule.getProject()).getFileIndex();

      ContentIterator ci = new ContentIterator() {
        public boolean processFile(final VirtualFile fileOrDir) {
          if (FlexCompilerHandler.includeInCompilation(myModule.getProject(), fileOrDir)) {
            if (/*!isTest && */projectFileIndex.isInTestSourceContent(fileOrDir)) {
              return true;
            }

            final VirtualFile rootForFile = projectFileIndex.getSourceRootForFile(fileOrDir);
            if (rootForFile != null) {
              final String packageText = VfsUtilCore.getRelativePath(fileOrDir.getParent(), rootForFile, '.');
              assert packageText != null;
              final String qName = (packageText.length() > 0 ? packageText + "." : "") + fileOrDir.getNameWithoutExtension();

              if (FlexCompilerHandler.isMxmlOrFxgOrASWithPublicDeclaration(myModule, fileOrDir, qName)) {
                addOption(rootElement, CompilerOptionInfo.INCLUDE_CLASSES_INFO, qName);
                noClasses.set(false);
              }
            }
          }
          return true;
        }
      };

      ModuleRootManager.getInstance(myModule).getFileIndex().iterateContent(ci);
      if (noClasses.get() && !ApplicationManager.getApplication().isUnitTestMode()) {
        throw new IOException(FlexBundle.message("nothing.to.compile.in.library", myModule.getName(), myBC.getName()));
      }
    }
    else {
      final InfoFromConfigFile info =
        FlexCompilerConfigFileUtil.getInfoFromConfigFile(myBC.getCompilerOptions().getAdditionalConfigFilePath());

      final String pathToMainClassFile = myFlexUnit ? FlexUtils.getPathToFlexUnitTempDirectory() + "/" + myBC.getMainClass()
                                                      + FlexUnitPrecompileTask.DOT_FLEX_UNIT_LAUNCHER_EXTENSION
                                                    : FlexUtils.getPathToMainClassFile(myBC.getMainClass(), myModule);

      if (pathToMainClassFile.isEmpty() && info.getMainClass(myModule) == null && !ApplicationManager.getApplication().isUnitTestMode()) {
        throw new IOException(FlexBundle.message("bc.incorrect.main.class", myBC.getMainClass(), myBC.getName(), myModule.getName()));
      }

      if (!pathToMainClassFile.isEmpty()) {
        addOption(rootElement, CompilerOptionInfo.MAIN_CLASS_INFO, pathToMainClassFile);
      }
    }

    addOption(rootElement, CompilerOptionInfo.OUTPUT_PATH_INFO, myBC.getOutputFilePath(false));
  }

  private void addOption(final Element rootElement, final CompilerOptionInfo info, final String rawValue) {
    if (!info.isApplicable(mySdk.getVersionString(), myBC.getNature())) {
      return;
    }

    final String value = FlexUtils.replacePathMacros(rawValue, myModule, myFlexmojos ? "" : mySdk.getHomePath());

    final List<String> elementNames = StringUtil.split(info.ID, ".");
    Element parentElement = rootElement;

    for (int i1 = 0; i1 < elementNames.size() - 1; i1++) {
      parentElement = getOrCreateElement(parentElement, elementNames.get(i1));
    }

    final String elementName = elementNames.get(elementNames.size() - 1);

    switch (info.TYPE) {
      case Group:
        assert false;
        break;
      case Boolean:
      case String:
      case Int:
      case File:
        final Element simpleElement = new Element(elementName, parentElement.getNamespace());
        simpleElement.setText(value);
        parentElement.addContent(simpleElement);
        break;
      case List:
        if (info.LIST_ELEMENTS.length == 1) {
          final Element listHolderElement = getOrCreateElement(parentElement, elementName);
          for (final String listElementValue : StringUtil.split(value, String.valueOf(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR))) {
            final Element child = new Element(info.LIST_ELEMENTS[0].NAME, listHolderElement.getNamespace());
            child.setText(listElementValue);
            listHolderElement.addContent(child);
          }
        }
        else {
          for (final String listEntry : StringUtil.split(value, String.valueOf(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR))) {
            final Element repeatableListHolderElement = new Element(elementName, parentElement.getNamespace());

            final List<String> values =
              StringUtil.split(listEntry, String.valueOf(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR), true, false);
            assert info.LIST_ELEMENTS.length == values.size() : info.ID + "=" + value;

            for (int i = 0; i < info.LIST_ELEMENTS.length; i++) {
              final Element child = new Element(info.LIST_ELEMENTS[i].NAME, repeatableListHolderElement.getNamespace());
              child.setText(values.get(i));
              repeatableListHolderElement.addContent(child);
            }

            parentElement.addContent(repeatableListHolderElement);
          }
        }
        break;
      case IncludeClasses:
        // todo implement
        break;
      case IncludeFiles:
        // todo implement
        break;
    }
  }

  private static Element getOrCreateElement(final Element parentElement, final String elementName) {
    Element child = parentElement.getChild(elementName, parentElement.getNamespace());
    if (child == null) {
      child = new Element(elementName, parentElement.getNamespace());
      parentElement.addContent(child);
    }
    return child;
  }

  private Pair<String, ValueSource> getValueAndSource(final CompilerOptionInfo info) {
    assert !info.isGroup() : info.DISPLAY_NAME;

    final String bcLevelValue = myBC.getCompilerOptions().getOption(info.ID);
    if (bcLevelValue != null) return Pair.create(bcLevelValue, ValueSource.BC);

    final String moduleLevelValue = myModuleLevelCompilerOptions.getOption(info.ID);
    if (moduleLevelValue != null) return Pair.create(moduleLevelValue, ValueSource.ModuleDefault);

    final String projectLevelValue = myProjectLevelCompilerOptions.getOption(info.ID);
    if (projectLevelValue != null) return Pair.create(projectLevelValue, ValueSource.ProjectDefault);

    return Pair.create(info.getDefaultValue(mySdk.getVersionString(), myBC.getNature()), ValueSource.GlobalDefault);
  }
}
