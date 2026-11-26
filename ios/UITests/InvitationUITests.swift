// SPDX-License-Identifier: AGPL-3.0-only
import XCTest

final class InvitationUITests: XCTestCase {
    @MainActor
    func testInviteAndEmailRegisterOnOnePageAndRespectDeveloperSettings() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let title = app.staticTexts["凭邀请加入"]
        let invite = app.textFields["invite.code"]
        let email = app.textFields["name@example.com"]
        let register = app.buttons["注册"]
        XCTAssertTrue(title.waitForExistence(timeout: 15), "使用尚未注册账号的测试模拟器执行首次启动验收。")
        XCTAssertTrue(invite.isHittable)
        XCTAssertFalse(app.secureTextFields["invite.code"].exists)
        XCTAssertTrue(email.isHittable)
        XCTAssertTrue(register.isHittable)
        XCTAssertFalse(register.isEnabled)
        let service = "https://101.132.60.65/secretchat"
        XCTAssertFalse(app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@ OR value == %@", service, service)).firstMatch.exists)
        let address = app.textFields["HTTPS 服务根地址（含部署前缀）"]
        XCTAssertFalse(address.exists)
        XCTAssertFalse(app.buttons["获取邮箱验证码"].exists)
        keepScreenshot(of: app, named: "invite-registration")
        invite.tap()
        invite.typeText("sample-invite")
        XCTAssertFalse(register.isEnabled)
        email.tap()
        email.typeText("demo@example.test")
        XCTAssertTrue(register.isEnabled)
        app.buttons["开发联调"].tap()
        let toggle = app.switches["服务已开启免验证码联调"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "1")
        let done = app.navigationBars.buttons["完成"]
        address.tap()
        address.typeKey("a", modifierFlags: .command)
        address.typeText("http://demo.test")
        XCTAssertTrue(done.isEnabled)
        done.tap()
        XCTAssertTrue(address.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "请填写 HTTPS 主机和路径")).firstMatch.exists)
        address.tap()
        address.typeKey("a", modifierFlags: .command)
        address.typeText("demo.test/secretchat")
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertEqual(toggle.value as? String, "0")
        done.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["获取邮箱验证码"].exists)
        XCTAssertFalse(register.isEnabled)

        app.terminate()
        app.launch()
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["获取邮箱验证码"].exists)
        app.buttons["开发联调"].tap()
        XCTAssertEqual(address.value as? String, "https://demo.test/secretchat")
        address.tap()
        address.typeKey("a", modifierFlags: .command)
        address.typeText(service)
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        done.tap()
        XCTAssertTrue(register.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["获取邮箱验证码"].exists)
    }

    @MainActor
    private func keepScreenshot(of app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
