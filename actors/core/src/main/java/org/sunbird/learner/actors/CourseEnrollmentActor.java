package org.sunbird.learner.actors;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.datasecurity.OneWayHashing;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.EkStepRequestUtil;
import org.sunbird.learner.util.Util;
import org.sunbird.telemetry.util.TelemetryUtil;

/**
 * This actor will handle course enrollment operation .
 *
 * @author Manzarul
 * @author Arvind
 */
@ActorConfig(
  tasks = {"enrollCourse"},
  asyncTasks = {}
)
public class CourseEnrollmentActor extends BaseActor {

  private static String EKSTEP_COURSE_SEARCH_QUERY =
      "{\"request\": {\"filters\":{\"contentType\": [\"Course\"], \"objectType\": [\"Content\"], \"identifier\": \"COURSE_ID_PLACEHOLDER\", \"status\": \"Live\"},\"limit\": 1}}";
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();

  private static final String DEFAULT_BATCH_ID = "1";

  /**
   * Receives the actor message and perform the course enrollment operation .
   *
   * @param message Object (Request)
   */
  @SuppressWarnings("unchecked")
  @Override
  public void onReceive(Request request) throws Throwable {

    Util.initializeContext(request, JsonKey.USER);
    // set request id fto thread loacl...
    ExecutionContext.setRequestId(request.getRequestId());

    if (request.getOperation().equalsIgnoreCase(ActorOperations.ENROLL_COURSE.getValue())) {
      Util.DbInfo courseEnrollmentdbInfo = Util.dbInfoMap.get(JsonKey.LEARNER_COURSE_DB);
      Util.DbInfo batchDbInfo = Util.dbInfoMap.get(JsonKey.COURSE_BATCH_DB);

      // objects of telemetry event...
      Map<String, Object> targetObject = new HashMap<>();
      List<Map<String, Object>> correlatedObject = new ArrayList<>();

      Map<String, Object> req = request.getRequest();
      String addedBy = (String) req.get(JsonKey.REQUESTED_BY);
      Map<String, Object> courseMap = (Map<String, Object>) req.get(JsonKey.COURSE);

      if (ProjectUtil.isNull(courseMap.get(JsonKey.BATCH_ID))) {
        courseMap.put(JsonKey.BATCH_ID, DEFAULT_BATCH_ID);
      } else {
        Response response =
            cassandraOperation.getRecordById(
                batchDbInfo.getKeySpace(),
                batchDbInfo.getTableName(),
                (String) courseMap.get(JsonKey.BATCH_ID));
        List<Map<String, Object>> responseList =
            (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
        if (responseList.isEmpty()) {
          throw new ProjectCommonException(
              ResponseCode.invalidCourseBatchId.getErrorCode(),
              ResponseCode.invalidCourseBatchId.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
        }
      }
      // check whether user already enroll for course
      Response dbResult =
          cassandraOperation.getRecordById(
              courseEnrollmentdbInfo.getKeySpace(),
              courseEnrollmentdbInfo.getTableName(),
              generateUserCoursesPrimaryKey(courseMap));
      List<Map<String, Object>> dbList = (List<Map<String, Object>>) dbResult.get(JsonKey.RESPONSE);
      if (!dbList.isEmpty()) {
        ProjectLogger.log("User Already Enrolled Course ");
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.userAlreadyEnrolledThisCourse.getErrorCode(),
                ResponseCode.userAlreadyEnrolledThisCourse.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }

      Map<String, String> headers = (Map<String, String>) request.getRequest().get(JsonKey.HEADER);
      String courseId = (String) courseMap.get(JsonKey.COURSE_ID);
      Map<String, Object> ekStepContent = getCourseObjectFromEkStep(courseId, headers);
      if (null == ekStepContent || ekStepContent.size() == 0) {
        ProjectLogger.log("Course Id not found in EkStep");
        ProjectCommonException exception =
            new ProjectCommonException(
                ResponseCode.invalidCourseId.getErrorCode(),
                ResponseCode.invalidCourseId.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      } else {
        Timestamp ts = new Timestamp(new Date().getTime());
        courseMap.put(JsonKey.COURSE_LOGO_URL, ekStepContent.get(JsonKey.APP_ICON));
        courseMap.put(JsonKey.CONTENT_ID, courseId);
        courseMap.put(JsonKey.COURSE_NAME, ekStepContent.get(JsonKey.NAME));
        courseMap.put(JsonKey.DESCRIPTION, ekStepContent.get(JsonKey.DESCRIPTION));
        courseMap.put(JsonKey.ADDED_BY, addedBy);
        courseMap.put(JsonKey.COURSE_ENROLL_DATE, ProjectUtil.getFormattedDate());
        courseMap.put(JsonKey.ACTIVE, ProjectUtil.ActiveStatus.ACTIVE.getValue());
        courseMap.put(JsonKey.STATUS, ProjectUtil.ProgressStatus.NOT_STARTED.getValue());
        courseMap.put(JsonKey.DATE_TIME, ts);
        courseMap.put(JsonKey.ID, generateUserCoursesPrimaryKey(courseMap));
        courseMap.put(JsonKey.COURSE_PROGRESS, 0);
        courseMap.put(JsonKey.LEAF_NODE_COUNT, ekStepContent.get(JsonKey.LEAF_NODE_COUNT));
        Response result =
            cassandraOperation.insertRecord(
                courseEnrollmentdbInfo.getKeySpace(),
                courseEnrollmentdbInfo.getTableName(),
                courseMap);
        sender().tell(result, self());

        targetObject =
            TelemetryUtil.generateTargetObject(
                (String) courseMap.get(JsonKey.USER_ID), JsonKey.USER, JsonKey.UPDATE, null);
        TelemetryUtil.generateCorrelatedObject(
            (String) courseMap.get(JsonKey.COURSE_ID),
            JsonKey.COURSE,
            "user.batch.course",
            correlatedObject);
        TelemetryUtil.generateCorrelatedObject(
            (String) courseMap.get(JsonKey.BATCH_ID),
            JsonKey.BATCH,
            "user.batch",
            correlatedObject);

        TelemetryUtil.telemetryProcessingCall(request.getRequest(), targetObject, correlatedObject);
        // TODO: for some reason, ES indexing is failing with Timestamp value. need to
        // check and
        // correct it.
        courseMap.put(JsonKey.DATE_TIME, ProjectUtil.formatDate(ts));
        insertUserCoursesToES(courseMap);
        return;
      }
    } else {
      onReceiveUnsupportedOperation(request.getOperation());
    }
  }

  private void insertUserCoursesToES(Map<String, Object> courseMap) {
    Request request = new Request();
    request.setOperation(ActorOperations.INSERT_USR_COURSES_INFO_ELASTIC.getValue());
    request.getRequest().put(JsonKey.USER_COURSES, courseMap);
    try {
      tellToAnother(request);
    } catch (Exception ex) {
      ProjectLogger.log("Exception Occured during saving user count to Es : ", ex);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> getCourseObjectFromEkStep(
      String courseId, Map<String, String> headers) {
    ProjectLogger.log("Requested course id is ==" + courseId, LoggerEnum.INFO.name());
    if (!StringUtils.isBlank(courseId)) {
      try {
        String query = EKSTEP_COURSE_SEARCH_QUERY.replaceAll("COURSE_ID_PLACEHOLDER", courseId);
        Map<String, Object> result = EkStepRequestUtil.searchContent(query, headers);
        if (null != result && !result.isEmpty()) {
          return ((List<Map<String, Object>>) result.get(JsonKey.CONTENTS)).get(0);
          //  return (Map<String, Object>) contentObject;
        }
      } catch (Exception e) {
        ProjectLogger.log(e.getMessage(), e);
      }
    }
    return null;
  }

  /**
   * This method will combined map values with delimiter and create an encrypted key.
   *
   * @param req Map<String , Object>
   * @return String encrypted value
   */
  private String generateUserCoursesPrimaryKey(Map<String, Object> req) {
    String userId = (String) req.get(JsonKey.USER_ID);
    String courseId = (String) req.get(JsonKey.COURSE_ID);
    String batchId = (String) req.get(JsonKey.BATCH_ID);
    return OneWayHashing.encryptVal(
        userId
            + JsonKey.PRIMARY_KEY_DELIMETER
            + courseId
            + JsonKey.PRIMARY_KEY_DELIMETER
            + batchId);
  }

  /**
   * This method will call the background job manager and update course enroll user count.
   *
   * @param operation String (operation name)
   * @param courseData Object
   * @param innerOperation String
   */
  @SuppressWarnings("unused")
  private void updateCoursemanagement(String operation, Object courseData, String innerOperation) {
    Request request = new Request();
    request.setOperation(operation);
    request.getRequest().put(JsonKey.COURSE_ID, courseData);
    request.getRequest().put(JsonKey.OPERATION, innerOperation);
    /*
     * userCountresponse.put(JsonKey.OPERATION, operation);
     * userCountresponse.put(JsonKey.COURSE_ID, courseData);
     * userCountresponse.getResult().put(JsonKey.OPERATION, innerOperation);
     */
    try {
      tellToAnother(request);
    } catch (Exception ex) {
      ProjectLogger.log("Exception Occured during saving user count to Es : ", ex);
    }
  }
}
