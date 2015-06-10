package se.bjurr.sscc;

import static com.atlassian.stash.user.UserType.SERVICE;
import static com.google.common.collect.Maps.newHashMap;
import static java.lang.Boolean.TRUE;
import static java.util.concurrent.TimeUnit.MINUTES;
import static se.bjurr.sscc.settings.SSCCSettings.sscSettings;

import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.bjurr.sscc.data.SSCCVerificationResult;
import se.bjurr.sscc.settings.SSCCSettings;

import com.atlassian.applinks.api.ApplicationLinkService;
import com.atlassian.stash.hook.HookResponse;
import com.atlassian.stash.hook.repository.PreReceiveRepositoryHook;
import com.atlassian.stash.hook.repository.RepositoryHookContext;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.user.DetailedUser;
import com.atlassian.stash.user.StashAuthenticationContext;
import com.atlassian.stash.user.UserAdminService;
import com.atlassian.stash.util.PageRequest;
import com.atlassian.stash.util.PageRequestImpl;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class SsccPreReceiveRepositoryHook implements PreReceiveRepositoryHook {
 private static Logger logger = LoggerFactory.getLogger(PreReceiveRepositoryHook.class);

 private ChangeSetsService changesetsService;

 private String hookName;

 private final StashAuthenticationContext stashAuthenticationContext;

 private final ApplicationLinkService applicationLinkService;

 private final UserAdminService userAdminService;

 public static final String STASH_USERS = "STASH_USERS";

 private final LoadingCache<String, Map<String, DetailedUser>> stashUsers = CacheBuilder.newBuilder().maximumSize(1)
   .refreshAfterWrite(5, MINUTES).build(new CacheLoader<String, Map<String, DetailedUser>>() {
    @Override
    public Map<String, DetailedUser> load(String key) throws Exception {
     final Map<String, DetailedUser> map = newHashMap();
     for (DetailedUser detailedUser : userAdminService.findUsers(new PageRequest() {

      public String getFilter() {
       // This method is not available in Sash 2.12.0, but in 3
       return "";
      }

      @Override
      public int getStart() {
       getFilter(); // Ensure save-actions does not remove the method
       return 0;
      }

      @Override
      public int getLimit() {
       return 1048575;
      }

      @Override
      public PageRequest buildRestrictedPageRequest(int arg0) {
       return new PageRequestImpl(0, 1048575);
      }
     }).getValues()) {
      map.put(detailedUser.getDisplayName(), detailedUser);
      map.put(detailedUser.getEmailAddress(), detailedUser);
      map.put(detailedUser.getName(), detailedUser);
     }
     return map;
    }
   });

 public SsccPreReceiveRepositoryHook(ChangeSetsService changesetsService,
   StashAuthenticationContext stashAuthenticationContext, ApplicationLinkService applicationLinkService,
   UserAdminService userAdminService) {
  this.hookName = "Simple Stash Commit Checker";
  this.changesetsService = changesetsService;
  this.stashAuthenticationContext = stashAuthenticationContext;
  this.applicationLinkService = applicationLinkService;
  this.userAdminService = userAdminService;
 }

 @VisibleForTesting
 public String getHookName() {
  return hookName;
 }

 @Override
 public boolean onReceive(RepositoryHookContext repositoryHookContext, Collection<RefChange> refChanges,
   HookResponse hookResponse) {
  try {
   SSCCRenderer ssccRenderer = new SSCCRenderer(this.stashAuthenticationContext, hookResponse);

   if (!hookName.isEmpty()) {
    ssccRenderer.println(hookName);
    ssccRenderer.println();
   }

   final SSCCSettings settings = sscSettings(repositoryHookContext.getSettings());
   final SSCCVerificationResult refChangeVerificationResults = new RefChangeValidator(repositoryHookContext,
     refChanges, settings, hookResponse, changesetsService, stashAuthenticationContext, ssccRenderer,
     applicationLinkService, stashUsers).validateRefChanges();

   new SSCCPrinter(settings, ssccRenderer).printVerificationResults(refChanges, refChangeVerificationResults);

   if (settings.isDryRun() && settings.getDryRunMessage().isPresent()) {
    ssccRenderer.println(settings.getDryRunMessage().get());
   }

   if (!settings.isDryRun()
     && !(settings.allowServiceUsers() && stashAuthenticationContext.getCurrentUser().getType().equals(SERVICE))) {
    return refChangeVerificationResults.isAccepted();
   }

   return TRUE;
  } catch (final Exception e) {
   final String message = "Error while validating reference changes. Will allow all of them. \"" + e.getMessage()
     + "\"";
   logger.error(message, e);
   hookResponse.out().println(message);
   return TRUE;
  }
 }

 @VisibleForTesting
 public void setChangesetsService(ChangeSetsService changesetsService) {
  this.changesetsService = changesetsService;
 }

 @VisibleForTesting
 public void setHookName(String hookName) {
  this.hookName = hookName;
 }

 @VisibleForTesting
 public static Logger getLogger() {
  return logger;
 }

 @VisibleForTesting
 public static void setLogger(Logger logger) {
  SsccPreReceiveRepositoryHook.logger = logger;
 }
}
