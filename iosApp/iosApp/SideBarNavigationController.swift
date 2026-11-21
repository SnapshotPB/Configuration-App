import UIKit
import ComposeApp

/// Hosts the Compose view controller in a `UINavigationController` to show a back button in the
/// vertical bar on iPhone Duo.
///
/// On iOS 27.1 and later, iPhone Duo puts navigation bar items in a side column. This controller
/// observes `SideBarBackKt` to show or hide a round back button there when the app is not on the
/// device list. The trait change handler updates the bar when the device folds, unfolds or turns.
///
/// On the folded iPhone Duo, a visible navigation bar has zero height and adds extra top safe
/// area. `viewDidLayoutSubviews` cancels that extra top inset on `content` so the layout stays
/// in the same vertical position.
///
/// On devices with no vertical bar, and on iOS versions before 27.1, the navigation bar stays
/// hidden and the layout does not change.
final class SideBarNavigationController: UINavigationController {
    private let content: UIViewController
    private let backItem: UIBarButtonItem
    private var observation: SideBarBackObservation?

    init(content: UIViewController) {
        self.content = content
        self.backItem = UIBarButtonItem(
            image: UIImage(systemName: "chevron.backward"),
            style: .plain,
            target: nil,
            action: nil
        )
        // Call the designated initializer directly. init(rootViewController:) calls
        // init(nibName:bundle:) on self, which fails when a subclass has stored properties.
        super.init(nibName: nil, bundle: nil)
        setViewControllers([content], animated: false)

        backItem.target = self
        backItem.action = #selector(goBack)
        backItem.accessibilityIdentifier = "sideBarBack"
        if #available(iOS 27.1, *) {
            backItem.axisBehavior = .verticalPreferred
        }

        observation = SideBarBackKt.observeSideBarBack { [weak self] state in
            self?.apply(state)
        }

        if #available(iOS 27.1, *) {
            _ = registerForTraitChanges(
                UITraitCollection.systemTraitsAffectingVerticalBarEdge
            ) { (self: Self, _: UITraitCollection) in
                self.updateBarVisibility()
            }
        }
        updateBarVisibility()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        observation?.cancel()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // The zero-height bar in vertical bar mode adds a top safe area to `content`.
        // This negative additional inset removes it so Compose gets the same insets as with no navigation controller.
        let topInset: CGFloat
        if !isNavigationBarHidden && navigationBar.frame.height == 0 {
            topInset = -max(0, navigationBar.frame.maxY - view.safeAreaInsets.top)
        } else {
            topInset = 0
        }
        if content.additionalSafeAreaInsets.top != topInset {
            content.additionalSafeAreaInsets.top = topInset
        }
    }

    private func apply(_ state: SideBarBackState) {
        backItem.accessibilityLabel = state.label
        content.navigationItem.leftBarButtonItem = state.visible ? backItem : nil
    }

    private func updateBarVisibility() {
        let showBar: Bool
        if #available(iOS 27.1, *) {
            showBar = traitCollection.verticalBarEdge != .unspecified
        } else {
            showBar = false
        }
        let hidden = !showBar
        if isNavigationBarHidden != hidden {
            setNavigationBarHidden(hidden, animated: false)
        }
        view.setNeedsLayout()
    }

    @objc private func goBack() {
        SideBarBackKt.requestSideBarBack()
    }
}
