// SPDX-License-Identifier: AGPL-3.0-only
import XCTest

final class InvitationUITests: XCTestCase {
    @MainActor
    func testFirstLaunchOpensInvitationAndReturnsFromDeveloperSettings() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let open = app.buttons["展开邀请"]
        XCTAssertTrue(open.waitForExistence(timeout: 15), "使用尚未注册账号的测试模拟器执行首次启动验收。")
        keepScreenshot(of: app, named: "invite-home")
        open.tap()
        XCTAssertTrue(app.staticTexts["验证你的邮箱"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["注册并打开联系人"].isEnabled)
        keepScreenshot(of: app, named: "invite-registration")
        app.buttons["开发联调"].tap()
        let toggle = app.switches["服务已开启免验证码联调"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertEqual(toggle.value as? String, "1")
        app.buttons["完成"].tap()
        XCTAssertTrue(app.staticTexts["注册这台设备"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["获取邮箱验证码"].exists)
        XCTAssertFalse(app.buttons["注册并打开联系人"].isEnabled)
    }

    @MainActor
    private func keepScreenshot(of app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
