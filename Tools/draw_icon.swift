import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Code-native brand mark: broken orbit around a navigation rift.
let output = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "app-icons/drawable/app_icon.png"
let ctx = CGContext(data: nil, width: 512, height: 512, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(red: 0.035, green: 0.065, blue: 0.085, alpha: 1));ctx.fill(CGRect(x: 0, y: 0, width: 512, height: 512))
ctx.setStrokeColor(CGColor(red: 0.55, green: 0.81, blue: 0.75, alpha: 1));ctx.setLineWidth(15);ctx.setLineCap(.square)
ctx.addArc(center: CGPoint(x: 256, y: 256), radius: 154, startAngle: .pi * 0.19, endAngle: .pi * 1.82, clockwise: false);ctx.strokePath()
ctx.setStrokeColor(CGColor(red: 0.91, green: 0.68, blue: 0.40, alpha: 1));ctx.setLineWidth(5)
ctx.addArc(center: CGPoint(x: 256, y: 256), radius: 124, startAngle: .pi * 0.27, endAngle: .pi * 0.78, clockwise: false);ctx.strokePath()
ctx.setFillColor(CGColor(red: 0.90, green: 0.94, blue: 0.92, alpha: 1))
ctx.move(to: CGPoint(x: 284, y: 405));ctx.addLine(to: CGPoint(x: 199, y: 244));ctx.addLine(to: CGPoint(x: 248, y: 244));ctx.addLine(to: CGPoint(x: 225, y: 106));ctx.addLine(to: CGPoint(x: 319, y: 282));ctx.addLine(to: CGPoint(x: 267, y: 282));ctx.closePath();ctx.fillPath()
ctx.setFillColor(CGColor(red: 0.91, green: 0.68, blue: 0.40, alpha: 1));ctx.fill(CGRect(x: 395, y: 247, width: 26, height: 18))
let url = URL(fileURLWithPath: output)
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
guard CGImageDestinationFinalize(dest) else {fatalError("Cannot write icon")}
print("Wrote \(output)")
