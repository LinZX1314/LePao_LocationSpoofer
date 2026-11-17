# 乐跑 4.7.0 接口清单

共 242 个端点，提取自 `kd/o.java` 的实际赋值。

## Advertise

| 端点 | 字段 |
| --- | --- |
| `Advertise/advLog` | `o4` |
| `Advertise/getAllSchoolAdvertise` | `R6` |
| `Advertise/recordAdvInfo` | `S6` |
| `Advertise/sdkAdvLog` | `N7` |

## Affiche

| 端点 | 字段 |
| --- | --- |
| `Affiche/add` | `A6` |
| `Affiche/detail` | `F4` |
| `Affiche/getAfficheComment` | `y6` |
| `Affiche/getAfficheScope` | `z6` |
| `Affiche/getClubSignRecord` | `x6` |
| `Affiche/getList` | `w6` |

## AppGame

| 端点 | 字段 |
| --- | --- |
| `AppGame/gameList` | `T6` |

## Appoint

| 端点 | 字段 |
| --- | --- |
| `Appoint/getAllSchoolFitClass` | `p5` |
| `Appoint/getSchoolFitClass` | `L0` |
| `Appoint/getSchoolFitClassDetail` | `w2` |
| `Appoint/getStudentClass` | `M0` |
| `Appoint/setOffAppointment` | `y2` |
| `Appoint/setOnAppointment` | `x2` |

## Article

| 端点 | 字段 |
| --- | --- |
| `Article/addCommentV220` | `H4` |
| `Article/addLaudV220` | `O4` |
| `Article/delCommentV220` | `K4` |
| `Article/delLaudV220` | `P4` |
| `Article/detail` | `E4` |
| `Article/matchList` | `j4` |
| `Article/recommendList` | `Y3` |
| `Article/subjectArticleList` | `Z3` |
| `Article/subjectCategoryList` | `a4` |

## Discover

| 端点 | 字段 |
| --- | --- |
| `Discover/getListV276` | `q0` |
| `Discover/getSchoolServiceMoreV277` | `V2` |
| `Discover/getSchoolServiceV330` | `T2` |
| `Discover/getUnreadMessageNumV330` | `r4` |

## Faq

| 端点 | 字段 |
| --- | --- |
| `Faq/category` | `B3` |

## FitnessTest

| 端点 | 字段 |
| --- | --- |
| `FitnessTest/getResultList` | `Z2` |
| `FitnessTest/getStudentList` | `R5` |
| `FitnessTest/getTask` | `O2` |
| `FitnessTest/getTaskDetail` | `Q5` |
| `FitnessTest/uploadResult` | `S5` |

## Game

| 端点 | 字段 |
| --- | --- |
| `Game/getGameCategory` | `F3` |
| `Game/getGameList` | `G3` |
| `Game/getRank` | `k3` |
| `Game/getSharePointsV148` | `Z4` |
| `Game/getTotalPrize` | `Y4` |
| `Game/verifyPassword` | `I3` |

## General

| 端点 | 字段 |
| --- | --- |
| `General/getAllShareImgSlogan` | `N4` |

## Home

| 端点 | 字段 |
| --- | --- |
| `Home/getBannerAndRecommend` | `E3` |

## Login

| 端点 | 字段 |
| --- | --- |
| `Login/Logout` | `g3` |
| `Login/RefreshToken` | `h3` |
| `Login/Register_san` | `N5` |
| `Login/UserPrivacy` | `E5` |
| `Login/UserProtocol` | `D5` |
| `Login/checkAppRedirect` | `l7` |
| `Login/getCodeV300` | `j7` |
| `Login/h5_slipcode` | `l5` |
| `Login/quickAuthStudent` | `m7` |
| `Login/quickLoginV339` | `k7` |

## Message

| 端点 | 字段 |
| --- | --- |
| `Message/allListV220` | `F2` |
| `Message/commentV220` | `M2` |
| `Message/laudV220` | `L2` |
| `Message/official` | `G2` |
| `Message/officialV210` | `b4` |
| `Message/schoolMessage` | `d3` |
| `Message/unreadMessageNumV220` | `W3` |

## More

| 端点 | 字段 |
| --- | --- |
| `More/getClubSignRecord` | `c3` |

## My

| 端点 | 字段 |
| --- | --- |
| `My/getSignupPoints` | `C3` |

## PrivateLetter

| 端点 | 字段 |
| --- | --- |
| `PrivateLetter/deletePrivateLetter` | `i0` |
| `PrivateLetter/detail` | `G6` |
| `PrivateLetter/send` | `H6` |

## Report

| 端点 | 字段 |
| --- | --- |
| `Report/appLog` | `p0` |
| `Report/getOssSign` | `t6` |

## Robot

| 端点 | 字段 |
| --- | --- |
| `Robot/appointRobotSports` | `Y7` |
| `Robot/cancelAppointRobot` | `f106b8` |
| `Robot/checkTermCert` | `f107c8` |
| `Robot/generateSportsCert` | `f109e8` |
| `Robot/getArAppointPeriodTime` | `X7` |
| `Robot/getArAppointPlan` | `W7` |
| `Robot/getSportsCertList` | `f108d8` |
| `Robot/robotAppointDetail` | `a8` |
| `Robot/robotProcessList` | `g8` |
| `Robot/robotSportsReport` | `f8` |
| `Robot/studentRobotAppointList` | `Z7` |

## Run

| 端点 | 字段 |
| --- | --- |
| `Run/AddTestPoints` | `d6` |
| `Run/DelTestPoints` | `f6` |
| `Run/GetAllZone` | `b6` |
| `Run/GetZonePoints` | `c6` |
| `Run/RollbackDel` | `h6` |
| `Run/StatusToDel` | `g6` |
| `Run/UpdateTestPoints` | `e6` |
| `Run/addRunComplain` | `B6` |
| `Run/getDeepknowInfo` | `n4` |
| `Run/getGeeInfo` | `P3` |
| `Run/getRank` | `j3` |
| `Run/getTimestampV278` | `O3` |
| `Run/getTotalRank` | `b3` |
| `Run/recordDetailV270` | `b5` |
| `Run/runScore` | `m5` |
| `Run/setRunLocationRecord` | `T3` |
| `Run/setRunZone` | `f3` |
| `Run/stopFreeRunV220` | `N3` |
| `Run/stopRunV278` | `M3` |
| `Run/uploadRunRecord` | `a5` |

## Run2

| 端点 | 字段 |
| --- | --- |
| `Run2/addSchFaceInfo` | `i4` |
| `Run2/addUserRelDevice` | `E2` |
| `Run2/allRecordList` | `n5` |
| `Run2/beforeRunV260` | `v5` |
| `Run2/checkSlipCode` | `X6` |
| `Run2/checkUserDeviceV278` | `B2` |
| `Run2/checkUserPassword` | `D2` |
| `Run2/faceVerify` | `P7` |
| `Run2/getCaptureStatus` | `S3` |
| `Run2/getDeviceCheckInfo` | `C2` |
| `Run2/getStudentVenaPoint` | `Q3` |
| `Run2/getVenaPointInfo` | `R3` |
| `Run2/gyroscope` | `U6` |
| `Run2/reportCheating` | `D4` |
| `Run2/simVerify` | `x3` |

## RunActivity

| 端点 | 字段 |
| --- | --- |
| `RunActivity/detail` | `A2` |
| `RunActivity/rank` | `z2` |

## RunStat

| 端点 | 字段 |
| --- | --- |
| `RunStat/adjustTermStat` | `c4` |
| `RunStat/getAllScore` | `f4` |
| `RunStat/getMyTermScore` | `g4` |

## School

| 端点 | 字段 |
| --- | --- |
| `School/getAcademyList` | `y5` |
| `School/getGymTeacherList` | `n0` |
| `School/getMajorList` | `o0` |
| `School/getMyAdminClass` | `j0` |
| `School/getMyGymClass` | `k0` |
| `School/nearbySchool` | `C4` |
| `School/setClass` | `m0` |
| `School/setGymClass` | `l0` |

## SchoolAdmin

| 端点 | 字段 |
| --- | --- |
| `SchoolAdmin/downloadReport` | `h4` |
| `SchoolAdmin/getModuleV277` | `u5` |
| `SchoolAdmin/getTermClassHead` | `M6` |
| `SchoolAdmin/getTermClassList` | `L6` |
| `SchoolAdmin/getTermClassStudent` | `N6` |
| `SchoolAdmin/getTermClassStudentRecord` | `P6` |
| `SchoolAdmin/getTermClassStudentScore` | `O6` |
| `SchoolAdmin/home` | `d4` |
| `SchoolAdmin/loginAdmin` | `s4` |

## SchoolAdminList

| 端点 | 字段 |
| --- | --- |
| `SchoolAdminList/main` | `e4` |

## SmartCup

| 端点 | 字段 |
| --- | --- |
| `SmartCup/checkLepaoPwd` | `p6` |
| `SmartCup/cupUseRecord` | `n6` |
| `SmartCup/getTips` | `j6` |
| `SmartCup/openCupById` | `l6` |
| `SmartCup/queryNearbyCup` | `i6` |
| `SmartCup/queryOpenCupPwd` | `r6` |
| `SmartCup/refreshCupState` | `o6` |
| `SmartCup/sendOpenCupPwd` | `s6` |
| `SmartCup/setOpenCupPwd` | `q6` |
| `SmartCup/stopUseCup` | `m6` |

## Statuses

| 端点 | 字段 |
| --- | --- |
| `Statuses/SchFollowStatuses` | `t4` |
| `Statuses/StatusesListByUid` | `w4` |
| `Statuses/addComment` | `I4` |
| `Statuses/addLaud` | `M4` |
| `Statuses/cancelHot` | `X4` |
| `Statuses/del` | `L4` |
| `Statuses/delComment` | `J4` |
| `Statuses/delLaud` | `V4` |
| `Statuses/detailV220` | `G4` |
| `Statuses/freshUploadCert` | `v6` |
| `Statuses/getSchoolComment` | `z4` |
| `Statuses/getVideoInfo` | `W6` |
| `Statuses/hotV300` | `u4` |
| `Statuses/joinBlacklist` | `U4` |
| `Statuses/joinStatusesHot` | `W4` |
| `Statuses/postV210` | `A4` |
| `Statuses/replyDetail` | `v4` |
| `Statuses/schoolTopicList` | `U3` |
| `Statuses/statusesLaudList ` | `Q4` |
| `Statuses/timelineV220` | `y4` |
| `Statuses/topicDetail` | `x4` |
| `Statuses/topicList` | `X3` |
| `Statuses/userReport` | `R4` |
| `Statuses/videoUploadCert` | `u6` |

## Student

| 端点 | 字段 |
| --- | --- |
| `Student/ClassInfo` | `z5` |
| `Student/InfoV14` | `x5` |
| `Student/MajorInfo` | `A5` |
| `Student/SubmitAlumniInfoV210` | `P5` |
| `Student/SubmitInfoV201` | `B5` |
| `Student/SubmitTeacherInfoV210` | `O5` |
| `Student/UploadPhoto` | `C5` |

## System

| 端点 | 字段 |
| --- | --- |
| `System/advConfig` | `m4` |

## Teacher

| 端点 | 字段 |
| --- | --- |
| `Teacher/studentFitnessStand` | `t2` |

## User

| 端点 | 字段 |
| --- | --- |
| `User/Update_user` | `l3` |
| `User/UploadBgImg` | `D6` |
| `User/User` | `w5` |
| `User/Userphoto` | `i3` |
| `User/UserphotoV210` | `m3` |
| `User/cancel` | `M7` |
| `User/getChildUser` | `B4` |
| `User/getDuibaLoginUrl` | `e3` |
| `User/getPointsRecord` | `W2` |
| `User/getPrivateLetterAcceptType` | `K6` |
| `User/getRelationList` | `F6` |
| `User/hxInfo` | `a3` |
| `User/myZone` | `C6` |
| `User/scanArCode` | `V7` |
| `User/statusesVideoAuth` | `S4` |
| `User/toBlacklist` | `J6` |
| `User/toRelation` | `E6` |
| `User/updatePrivateLetterAcceptType` | `Q6` |

## UserAccount

| 端点 | 字段 |
| --- | --- |
| `UserAccount/bindAccount` | `p3` |
| `UserAccount/checkPwd` | `o3` |
| `UserAccount/getQRCodeInfo` | `I6` |
| `UserAccount/resetPhone` | `L5` |
| `UserAccount/resetPwd` | `n3` |
| `UserAccount/unbindAccount` | `q3` |

## UserLog

| 端点 | 字段 |
| --- | --- |
| `UserLog/uploadAuthOpenLog` | `l4` |
| `UserLog/uploadWelfareCenterLog` | `k4` |

## UserPoint

| 端点 | 字段 |
| --- | --- |
| `UserPoint/pointRule` | `Y2` |
| `UserPoint/totalRank` | `X2` |

## UserRank

| 端点 | 字段 |
| --- | --- |
| `UserRank/rankList` | `S2` |

## bracelet

| 端点 | 字段 |
| --- | --- |
| `bracelet/courseDeviceGlobalSetting` | `R2` |
| `bracelet/courseDeviceHistory` | `T7` |
| `bracelet/courseDeviceStatists` | `P2` |
| `bracelet/courseDeviceStudentStatists` | `U7` |
| `bracelet/courseDeviceStudents` | `S7` |
| `bracelet/saveCourseDeviceGlobalSetting` | `Q2` |
| `bracelet/todayStatists` | `R7` |
| `bracelet/uploadRecord` | `Q7` |

## discover

| 端点 | 字段 |
| --- | --- |
| `discover/getSchoolNews` | `J7` |
| `discover/getSubModule` | `g7` |
| `discover/getSubModuleV330` | `h7` |
| `discover/schoolNewsCategory` | `I7` |

## home

| 端点 | 字段 |
| --- | --- |
| `home/details` | `V3` |

## login

| 端点 | 字段 |
| --- | --- |
| `login/ForgetpwdV14` | `J5` |
| `login/GetCode` | `K5` |
| `login/GetCodeV148` | `M5` |
| `login/LoginV14` | `H5` |
| `login/Login_sanV148` | `G5` |
| `login/RegisterV14` | `I5` |
| `login/bind_phoneV14` | `u3` |

## my

| 端点 | 字段 |
| --- | --- |
| `my/Finishranks` | `t3` |
| `my/Nowranks` | `s3` |
| `my/Task` | `r3` |

## report

| 端点 | 字段 |
| --- | --- |
| `report/getOssSts` | `O7` |

## run

| 端点 | 字段 |
| --- | --- |
| `run/getSharePoints` | `V6` |

## system

| 端点 | 字段 |
| --- | --- |
| `system/GetRankslog` | `K3` |
| `system/Rankslog` | `L3` |
| `system/feedback` | `w3` |
| `system/gameBanner` | `D3` |
| `system/get_game_statu` | `F5` |
| `system/join_game` | `H3` |
| `system/leave_game` | `J3` |
| `system/update` | `v3` |
