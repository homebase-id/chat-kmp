package id.homebase.core.contacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.homebase.core.util.topViewController
import platform.Contacts.CNContact
import platform.Contacts.CNContactStore
import platform.Contacts.CNLabelOther
import platform.Contacts.CNLabelPhoneNumberMobile
import platform.Contacts.CNLabelURLAddressHomePage
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNMutableContact
import platform.Contacts.CNPhoneNumber
import platform.ContactsUI.CNContactViewController
import platform.ContactsUI.CNContactViewControllerDelegateProtocol
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.UINavigationController
import platform.darwin.NSObject

internal fun DeviceContact.toCNContact(): CNMutableContact {
    val contact = CNMutableContact()
    val noStructuredName = givenName.isBlank() && surname.isBlank()
    contact.setGivenName(if (noStructuredName) displayName else givenName)
    contact.setFamilyName(surname)
    contact.setOrganizationName(organization)
    contact.setPhoneNumbers(phones.map {
        CNLabeledValue(label = CNLabelPhoneNumberMobile, value = CNPhoneNumber(stringValue = it))
    })
    contact.setEmailAddresses(emails.map { CNLabeledValue(label = CNLabelOther, value = NSString.create(string = it)) })
    if (website.isNotBlank()) {
        contact.setUrlAddresses(listOf(CNLabeledValue(label = CNLabelURLAddressHomePage, value = NSString.create(string = website))))
    }
    return contact
}

private class IosDeviceContactSaver : DeviceContactSaver {
    // CNContactViewController.delegate is weak; without this the delegate is collected and Done does nothing.
    private var delegate: CNContactViewControllerDelegateProtocol? = null

    override fun open(contact: DeviceContact): Boolean {
        val presenter = topViewController() ?: return false
        val viewController = CNContactViewController.viewControllerForNewContact(contact.toCNContact())
        viewController.contactStore = CNContactStore()
        val completionDelegate = object : NSObject(), CNContactViewControllerDelegateProtocol {
            override fun contactViewController(
                viewController: CNContactViewController,
                didCompleteWithContact: CNContact?,
            ) {
                viewController.dismissViewControllerAnimated(true) { delegate = null }
            }
        }
        delegate = completionDelegate
        viewController.delegate = completionDelegate
        presenter.presentViewController(UINavigationController(rootViewController = viewController), animated = true, completion = null)
        return true
    }
}

@Composable
actual fun rememberDeviceContactSaver(): DeviceContactSaver? = remember { IosDeviceContactSaver() }
