/*
 * Copyright 2019, Shirou WAKAYAMA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package info.tdoc.exporter.trace.xray;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.opencensus.common.Function;
import io.opencensus.common.Functions;
import io.opencensus.common.Timestamp;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.Status;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.export.SpanData;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 *
 * document: https://docs.aws.amazon.com/xray/latest/devguide/xray-api-segmentdocuments.html
 *
 *
 *
 */
public class TraceSegment {
  private static final Integer MaxAge = 60 * 60 * 24 * 28; // 28Day
  private static final Integer MaxSkew = 60 * 5; // 5m
  private static final String VersionNo = "1";
  private static final Pattern reInvalidSpanCharacters = Pattern.compile("");
  private static final Integer maxSegmentNameLength = 200;
  private static final String defaultSegmentName = "span";

  @JsonProperty("name")
  public String name;

  @JsonProperty("id")
  public String id;

  @JsonProperty("start_time")
  public double startTime;

  @JsonProperty("trace_id")
  public String traceId;

  @JsonProperty("parent_id")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String parentId;

  @JsonProperty("end_time")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public double endTime;

  @JsonProperty("in_progress")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Boolean inProgress;

  @JsonProperty("type")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String type;

  @JsonProperty("namespace")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String nameSpace;

  @JsonProperty("error")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Boolean error;

  @JsonProperty("throttle")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Boolean throttle;

  @JsonProperty("user")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String user;

  @JsonProperty("annotations")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Map<String, Object> annotations;

  @JsonProperty("cause")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Cause cause;

  @JsonProperty("origin")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String origin;

  @JsonProperty("subsegments")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public List<TraceSegment> subsegments;


  public class Cause {
    @JsonProperty("exceptions")
    public List<Exceptions> exceptions;

    public class Exceptions {
      @JsonProperty("id")
      public String id;

      @JsonProperty("message")
      public String message;
    }
  }

  public TraceSegment(String name, SpanData sd) {
    SpanContext sc = sd.getContext();
    if (name == null || name.equals("")) {
      this.name = fixSegmentName(sd.getName());
    } else {
      this.name = name;
    }

    // IDs
    this.id = convertToAmazonSpanID(sc.getSpanId());
    this.traceId = convertToAmazonTraceID(sc.getTraceId());
    SpanId parentId = sd.getParentSpanId();
    if (parentId != null && parentId.isValid()) {
      this.parentId = convertToAmazonSpanID(parentId);
    }

    // Time
    this.startTime = toEpochSeconds(sd.getStartTimestamp());
    Timestamp endTime = sd.getEndTimestamp();
    if (endTime == null) {
      this.inProgress = true;
    } else {
      this.endTime = toEpochSeconds(endTime);
    }

    if (sd.getHasRemoteParent() != null) {
      this.nameSpace = "remote";
    }

    makeCause(sd.getStatus());
    this.annotations = this.makeAnnotations(sd.getAttributes());
  }

  /*
   * convertToAmazonSpanID generates an Amazon spanID from a SpanID - a 64-bit identifier
   * for the segment, unique among segments in the same trace, in 16 hexadecimal digits.
   */
  private static String convertToAmazonSpanID(SpanId spanId) {
    byte[] v = spanId.getBytes();
    if (v.equals("")) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (byte b : Arrays.copyOfRange(v, 0, 8)) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /*
   * converts a trace ID to the Amazon format.
   *
   */
  private static String convertToAmazonTraceID(TraceId traceId) {
    long epochNow = Instant.now(Clock.systemUTC()).getEpochSecond();
    long epoch = ByteBuffer.wrap(Arrays.copyOfRange(traceId.getBytes(), 0, 4)).getInt();

    long delta = epochNow - epoch;
    if (delta > MaxAge || delta < -MaxSkew) {
      epoch = epochNow;
    }

    StringBuilder sb = new StringBuilder();

    sb.append(VersionNo);
    sb.append("-");

    byte[] epochByte = ByteBuffer.allocate(8).putLong(epoch).array();
    for (byte b : Arrays.copyOfRange(epochByte, 4, 8)) {
      sb.append(String.format("%02x", b));
    }

    sb.append("-");
    // overwrite with identifier
    for (byte b : Arrays.copyOfRange(traceId.getBytes(), 4, 16)) {
      sb.append(String.format("%02x", b));
    }

    return sb.toString();
  }

  private void makeCause(Status status) {
    // TODO: description is not finished.
    if (status == null || status.isOk()) {
      return;
    }

    if (status.equals(Status.RESOURCE_EXHAUSTED)) {
      this.throttle = true;
      return;
    }
    this.error = true;
  }

  private Map<String, Object> makeAnnotations(SpanData.Attributes attrib) {
    if (attrib.getAttributeMap().entrySet().size() == 0) {
      return null;
    }

    Map<String, Object> ret = new HashMap<String, Object>();
    for (Map.Entry<String, AttributeValue> label : attrib.getAttributeMap().entrySet()) {
      ret.put(label.getKey(), attributeValueToObject(label.getValue()));
    }

    return ret;
  }

  /*
   * fixSegmentName removes any invalid characters from the span name.  AWS X-Ray defines
   * the list of valid characters here:
   * https://docs.aws.amazon.com/xray/latest/devguide/xray-api-segmentdocuments.html
   */
  private static String fixSegmentName(String name) {
    Matcher m = reInvalidSpanCharacters.matcher(name);
    if (m.matches()) {
      // only allocate for ReplaceAllString if we need to
      name = m.replaceAll("");
    }
    if (name.length() > maxSegmentNameLength) {
      name = name.substring(0, maxSegmentNameLength);
    } else if (name.length() == 0) {
      name = defaultSegmentName;
    }

    return name;
  }

  private static double toEpochSeconds(Timestamp timestamp) {
    return timestamp.getSeconds() + NANOSECONDS.toMillis(timestamp.getNanos()) / 1000.0;
  }

  private static Object attributeValueToObject(AttributeValue attributeValue) {
    return attributeValue.match(
        stringAttributeValueFunction,
        booleanAttributeValueFunction,
        longAttributeValueFunction,
        doubleAttributeValueFunction,
        Functions.returnNull());
  }

  // Constant functions for AttributeValue.
  private static final Function<? super String, Object> stringAttributeValueFunction =
      new Function<String, Object>() {
        @Override
        public Object apply(String value) {
          return value;
        }
      };
  private static final Function<? super Boolean, Object> booleanAttributeValueFunction =
      new Function<Boolean, Object>() {
        @Override
        public Object apply(Boolean value) {
          return value;
        }
      };
  private static final Function<? super Long, Object> longAttributeValueFunction =
      new Function<Long, Object>() {
        @Override
        public Object apply(Long value) {
          return value;
        }
      };
  private static final Function<? super Double, Object> doubleAttributeValueFunction =
      new Function<Double, Object>() {
        @Override
        public Object apply(Double value) {
          return value;
        }
      };
}
