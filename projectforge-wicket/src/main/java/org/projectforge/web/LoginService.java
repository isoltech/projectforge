package org.projectforge.web;

import javax.annotation.PostConstruct;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.wicket.RestartResponseException;
import org.apache.wicket.markup.html.WebPage;
import org.apache.wicket.request.http.WebRequest;
import org.apache.wicket.request.http.WebResponse;
import org.projectforge.Const;
import org.projectforge.business.ldap.LdapMasterLoginHandler;
import org.projectforge.business.ldap.LdapSlaveLoginHandler;
import org.projectforge.business.login.LoginDefaultHandler;
import org.projectforge.business.login.LoginHandler;
import org.projectforge.business.login.LoginProtection;
import org.projectforge.business.login.LoginResult;
import org.projectforge.business.login.LoginResultStatus;
import org.projectforge.business.multitenancy.TenantRegistry;
import org.projectforge.business.multitenancy.TenantRegistryMap;
import org.projectforge.business.user.UserGroupCache;
import org.projectforge.business.user.UserXmlPreferencesCache;
import org.projectforge.business.user.filter.CookieService;
import org.projectforge.business.user.filter.UserFilter;
import org.projectforge.business.user.service.UserService;
import org.projectforge.framework.persistence.user.api.UserContext;
import org.projectforge.framework.persistence.user.entities.PFUserDO;
import org.projectforge.web.admin.SystemUpdatePage;
import org.projectforge.web.session.MySession;
import org.projectforge.web.wicket.ClientIpResolver;
import org.projectforge.web.wicket.WicketUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class LoginService
{
  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(LoginService.class);

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private UserService userService;

  @Autowired
  private CookieService cookieService;

  @Value("${projectforge.login.handlerClass}")
  private String loginHandlerClass;

  private LoginHandler loginHandler;

  @PostConstruct
  public void init()
  {
    switch (getLoginHandlerClass()) {
      case "LdapMasterLoginHandler":
        this.loginHandler = applicationContext.getBean(LdapMasterLoginHandler.class);
        break;
      case "LdapSlaveLoginHandler":
        this.loginHandler = applicationContext.getBean(LdapSlaveLoginHandler.class);
        break;
      default:
        this.loginHandler = applicationContext.getBean(LoginDefaultHandler.class);
    }
  }

  private void internalLogin(final WebPage page, final PFUserDO user)
  {
    final UserContext userContext = new UserContext(PFUserDO.createCopyWithoutSecretFields(user),
        getUserGroupCache());
    ((MySession) page.getSession()).login(userContext, page.getRequest());
    UserFilter.login(WicketUtils.getHttpServletRequest(page.getRequest()), userContext);
  }

  public TenantRegistry getTenantRegistry()
  {
    return TenantRegistryMap.getInstance().getTenantRegistry();
  }

  public UserGroupCache getUserGroupCache()
  {
    return getTenantRegistry().getUserGroupCache();
  }

  /**
   * @param page
   * @param username
   * @param password
   * @param userWantsToStayLoggedIn
   * @param defaultPage
   * @return i18n key of the validation error message if not successfully logged in, otherwise null.
   */
  public LoginResultStatus internalCheckLogin(final WebPage page, final String username,
      final String password, final boolean userWantsToStayLoggedIn, final Class<? extends WebPage> defaultPage)
  {
    final LoginResult loginResult = checkLogin(username, password);
    final PFUserDO user = loginResult.getUser();
    if (user == null || loginResult.getLoginResultStatus() != LoginResultStatus.SUCCESS) {
      return loginResult.getLoginResultStatus();
    }
    if (UserFilter.isUpdateRequiredFirst() == true) {
      internalLogin(page, user);
      log.info("Admin login for maintenance (data-base update) successful for user '" + username + "'.");
      throw new RestartResponseException(SystemUpdatePage.class);
    }
    log.info("User successfully logged in: " + user.getDisplayUsername());
    if (userWantsToStayLoggedIn == true) {
      final PFUserDO loggedInUser = userService.getById(user.getId());
      final Cookie cookie = new Cookie(Const.COOKIE_NAME_FOR_STAY_LOGGED_IN, loggedInUser.getId()
          + ":"
          + loggedInUser.getUsername()
          + ":"
          + userService.getStayLoggedInKey(user.getId()));
      cookieService.addStayLoggedInCookie(WicketUtils.getHttpServletRequest(page.getRequest()), WicketUtils.getHttpServletResponse(page.getResponse()), cookie);
    }
    internalLogin(page, user);
    // Do not redirect to requested page in maintenance mode (update required first):
    if (UserFilter.isUpdateRequiredFirst() == true) {
      throw new RestartResponseException(SystemUpdatePage.class);
    }
    page.continueToOriginalDestination();
    // Redirect only if not a redirect is set by Wicket.
    throw new RestartResponseException(defaultPage);
  }

  /**
   * @see LoginHandler#checkLogin(String, String)
   */
  private LoginResult checkLogin(final String username, final String password)
  {
    if (loginHandler == null) {
      log.warn("No login possible because no login handler is defined yet.");
      return new LoginResult().setLoginResultStatus(LoginResultStatus.FAILED);
    }
    if (username == null || password == null) {
      return new LoginResult().setLoginResultStatus(LoginResultStatus.FAILED);
    }
    final LoginProtection loginProtection = LoginProtection.instance();
    final String clientIpAddress = ClientIpResolver.getClientIp();
    final long offset = loginProtection.getFailedLoginTimeOffsetIfExists(username, clientIpAddress);
    if (offset > 0) {
      final String seconds = String.valueOf(offset / 1000);
      log.warn("The account for '"
          + username
          + "' is locked for "
          + seconds
          + " seconds due to failed login attempts. Please try again later.");
      final int numberOfFailedAttempts = loginProtection.getNumberOfFailedLoginAttempts(username, clientIpAddress);
      return new LoginResult().setLoginResultStatus(LoginResultStatus.LOGIN_TIME_OFFSET).setMsgParams(seconds,
          String.valueOf(numberOfFailedAttempts));
    }
    final LoginResult result = loginHandler.checkLogin(username, password);
    if (result.getLoginResultStatus() == LoginResultStatus.SUCCESS) {
      loginProtection.clearLoginTimeOffset(username, clientIpAddress);
    } else if (result.getLoginResultStatus() == LoginResultStatus.FAILED) {
      loginProtection.incrementFailedLoginTimeOffset(username, clientIpAddress);
    }
    return result;
  }

  /**
   * If given then this login handler will be used instead of {@link LoginDefaultHandler}. For ldap please use e. g.
   * org.projectforge.ldap.LdapLoginHandler.
   *
   * @return the loginHandlerClass or "" if not given
   */
  public String getLoginHandlerClass()
  {
    if (loginHandlerClass != null) {
      return loginHandlerClass;
    }
    return "";
  }

  public void logout(final MySession mySession, final WebRequest request, final WebResponse response,
      final UserXmlPreferencesCache userXmlPreferencesCache, MenuBuilder menuBuilder)
  {
    final Cookie stayLoggedInCookie = cookieService.getStayLoggedInCookie(WicketUtils.getHttpServletRequest(request));
    logout(mySession, stayLoggedInCookie, userXmlPreferencesCache, menuBuilder);
    if (stayLoggedInCookie != null) {
      response.addCookie(stayLoggedInCookie);
    }
  }

  public void logout(final MySession mySession, final HttpServletRequest request,
      final HttpServletResponse response,
      final UserXmlPreferencesCache userXmlPreferencesCache, MenuBuilder menuBuilder)
  {
    final Cookie stayLoggedInCookie = cookieService.getStayLoggedInCookie(request);
    logout(mySession, stayLoggedInCookie, userXmlPreferencesCache, menuBuilder);
    if (stayLoggedInCookie != null) {
      response.addCookie(stayLoggedInCookie);
    }
  }

  private void logout(final MySession mySession, final Cookie stayLoggedInCookie,
      final UserXmlPreferencesCache userXmlPreferencesCache, MenuBuilder menuBuilder)
  {
    final PFUserDO user = mySession.getUser();
    if (user != null) {
      userXmlPreferencesCache.flushToDB(user.getId());
      userXmlPreferencesCache.clear(user.getId());
      if (menuBuilder != null) {
        menuBuilder.expireMenu(user.getId());
      }
    }
    mySession.logout();
    if (stayLoggedInCookie != null) {
      stayLoggedInCookie.setMaxAge(0);
      stayLoggedInCookie.setValue(null);
      stayLoggedInCookie.setPath("/");
    }
  }

}
