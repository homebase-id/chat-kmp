import Foundation
import ffmpegkit
import ComposeApp

/// Swift implementation of FFmpegKitBridge that wraps the FFmpegKit framework.
/// This is injected into the Kotlin framework at app startup.
class FFmpegKitBridgeImpl: FFmpegKitBridge {
    
    func executeFFmpeg(command: String) -> FFmpegResult {
        let session = FFmpegKit.execute(command)
        let isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
        let failStackTrace = session?.getFailStackTrace()
        return FFmpegResult(isSuccess: isSuccess, failStackTrace: failStackTrace)
    }
    
    func getMediaInformation(filePath: String) -> MediaInfo? {
        guard let session = FFprobeKit.getMediaInformation(filePath) else {
            return nil
        }
        
        guard let mediaInformation = session.getMediaInformation() else {
            return nil
        }
        
        guard let streams = mediaInformation.getStreams() as? [StreamInformation] else {
            return nil
        }
        
        var streamInfoList: [StreamInfo] = []
        
        for stream in streams {
            let streamType = stream.getType()
            
            // Get tags
            var tagsDict: [String: String] = [:]
            if let tags = stream.getTags() as? [String: String] {
                tagsDict = tags
            }
            
            // Get rotation from tags or side_data_list
            var rotation: Int32? = nil
            
            // Method 1: Check 'rotate' tag (older videos)
            if let rotateValue = tagsDict["rotate"], let rotateInt = Int32(rotateValue) {
                rotation = rotateInt
            }
            
            // Method 2: Check side_data_list for Display Matrix (newer videos)
            if rotation == nil {
                if let sideDataList = stream.getProperty("side_data_list") as? [[String: Any]] {
                    for sideData in sideDataList {
                        if let sideDataType = sideData["side_data_type"] as? String,
                           sideDataType == "Display Matrix" {
                            if let rotationValue = sideData["rotation"] {
                                if let rotInt = rotationValue as? Int32 {
                                    rotation = rotInt
                                } else if let rotDouble = rotationValue as? Double {
                                    rotation = Int32(rotDouble)
                                } else if let rotString = rotationValue as? String,
                                          let rotDouble = Double(rotString) {
                                    rotation = Int32(rotDouble)
                                }
                            }
                        }
                    }
                }
            }
            
            let streamInfo = StreamInfo(
                type: streamType,
                tags: tagsDict,
                rotation: rotation.map { KotlinInt(value: $0) }
            )
            streamInfoList.append(streamInfo)
        }
        
        return MediaInfo(streams: streamInfoList)
    }
}
