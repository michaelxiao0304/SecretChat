import Foundation
import XCTest

func httpReply(_ status: Int, _ body: Data, headers: [String: String] = [:]) throws -> (Data, URLResponse) {
    let url = try XCTUnwrap(URL(string: "https://service.test"))
    let http = try XCTUnwrap(HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers))
    return (body, http)
}
