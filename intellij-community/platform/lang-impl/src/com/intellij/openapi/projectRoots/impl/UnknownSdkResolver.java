// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.projectRoots.impl;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownloadTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extension point to resolve missing SDKs in a project,
 * it is executed from a background thread on project open
 * or after project model changes
 */
public interface UnknownSdkResolver {
  @ApiStatus.Internal
  ExtensionPointName<UnknownSdkResolver> EP_NAME = ExtensionPointName.create("com.intellij.unknownSdkResolver");

  /**
   * Creates search context. The same object is used to process all unknown SDKs in the project,
   * it a good idea to cache any heavy operations inside that instance.
   * That method must be cheap to run.
   * <br/>
   * Return {@code null} to ignore a given request
   */
  @Nullable
  UnknownSdkLookup createResolver(@Nullable Project project, @NotNull ProgressIndicator indicator);

  interface UnknownSdkLookup {
    /**
     * The implementation should check on a local machine (both in existing SDKs and on the disk)
     * for a possible matching SDK
     *
     * @return best match or {@code null} if there is no fix found
     */
    @Nullable
    LocalSdkFix proposeLocalFix(@NotNull UnknownSdk sdk, @NotNull ProgressIndicator indicator);

    /**
     * Checks the internet for a possible download to fix the SDK.
     *
     * @return the best possible download for {@link com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownloadTracker},
     * or {@code null} if there is no fix found
     */
    @Nullable
    DownloadSdkFix proposeDownload(@NotNull UnknownSdk sdk, @NotNull ProgressIndicator indicator);
  }

  /**
   * Contains information and requirements for the SDK to resolve
   */
  @ApiStatus.NonExtendable
  interface UnknownSdk {
    /**
     * Type of SDK to resolve with {@link UnknownSdkLookup}
     */
    @NotNull
    SdkType getSdkType();

    /**
     * A missing SDK name, if known. A detector implementation may rely on the naming
     * to use it for a better decision on the SDK to suggest.
     */
    @Nullable
    default String getSdkName() { return null; }

    /**
     * A minimum version (inclusive) of a resolved SDK.
     * {@link SdkType#versionComparator()} will be used for comparison.
     */
    @Nullable
    default String getSdkMinVersionRequirement() { return null; }

    /**
     * A maximum version (exclusive) of a resolved SDK.
     * {@link SdkType#versionComparator()} will be used for comparison.
     */
    @Nullable
    default String getSdkMaxVersionRequirement() { return null; }
  }

  /**
   * Locally detected SDK to fix immediately
   */
  interface LocalSdkFix {
    /**
     * @return the resolved home of the detected SDK to configure
     */
    @NotNull String getExistingSdkHome();

    /**
     * @return the actual version string of the SDK
     */
    @NotNull String getVersionString();

    /**
     * @return suggested name for an SDK to be created, still, the name could
     * be altered to avoid conflicts
     */
    @NotNull
    String getSuggestedSdkName();
  }

  /**
   * A download suggestion to fix a missing SDK by downloading it
   * @see com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownload
   */
  interface DownloadSdkFix {
    @NotNull
    String getDownloadDescription();

    @NotNull
    SdkDownloadTask createTask(@NotNull ProgressIndicator indicator);
  }
}
