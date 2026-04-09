package id.homebase.core

import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject

object MainViewControllerRef {
    var instance: UIViewController? = null
}

class AuthPresentationContextProvider : NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow? {
        return MainViewControllerRef.instance?.view?.window
    }
}