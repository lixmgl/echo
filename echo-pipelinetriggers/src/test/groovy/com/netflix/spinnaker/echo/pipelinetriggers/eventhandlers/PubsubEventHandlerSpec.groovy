/*
 * Copyright 2017 Google, Inc.
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

package com.netflix.spinnaker.echo.pipelinetriggers.eventhandlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.echo.model.Metadata
import com.netflix.spinnaker.echo.model.pubsub.MessageDescription
import com.netflix.spinnaker.echo.model.pubsub.PubsubSystem
import com.netflix.spinnaker.echo.model.trigger.PubsubEvent
import com.netflix.spinnaker.echo.test.RetrofitStubs
import com.netflix.spinnaker.kork.artifacts.model.Artifact
import com.netflix.spinnaker.kork.artifacts.model.ExpectedArtifact
import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class PubsubEventHandlerSpec extends Specification implements RetrofitStubs {
  def registry = new NoopRegistry()
  def objectMapper = new ObjectMapper()
  def handlerSupport = new EventHandlerSupport()

  @Shared
  def goodArtifacts = [new Artifact(name: 'myArtifact', type: 'artifactType')]
  
  @Shared
  def badExpectedArtifacts = [
    new ExpectedArtifact(
      matchArtifact: new Artifact(
        name: 'myBadArtifact',
        type: 'artifactType',
      ),
      id: 'badId'
    )
  ]

  @Shared
  def goodExpectedArtifacts = [
    new ExpectedArtifact(
      matchArtifact: new Artifact(
        name: 'myArtifact',
        type: 'artifactType',
      ),
      id: 'goodId'
    )
  ]

  @Shared
  def goodRegexExpectedArtifacts = [
    new ExpectedArtifact(
      matchArtifact: new Artifact(
        name: 'myArtifact',
        type: 'artifact.*',
      ),
      id: 'goodId'
    )
  ]

  @Subject
  def eventHandler = new PubsubEventHandler(registry, objectMapper)

  @Unroll
  def "triggers pipelines for successful builds for Google pubsub"() {
    given:
    def pipeline = createPipelineWith(goodExpectedArtifacts, trigger)
    def pipelines = handlerSupport.pipelineCache(pipeline)

    when:
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)

    then:
    matchingPipelines.size() == 1
    matchingPipelines[0].application == pipeline.application && matchingPipelines[0].name == pipeline.name

    where:
    event                                                                                                     | trigger
    createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", null, [:])          | enabledGooglePubsubTrigger
    createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", [], [:])            | enabledGooglePubsubTrigger
    createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", goodArtifacts, [:]) | enabledGooglePubsubTrigger.withExpectedArtifactIds(goodExpectedArtifacts*.id)
    createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", goodArtifacts, [:]) | enabledGooglePubsubTrigger.withExpectedArtifactIds(goodRegexExpectedArtifacts*.id)
    createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", goodArtifacts, [:]) | enabledGooglePubsubTrigger // Trigger doesn't care about artifacts.
    // TODO(jacobkiefer): Add Kafka cases when that is implemented.
  }

  def "attaches Google pubsub trigger to the pipeline"() {
    given:
    def pipelines = handlerSupport.pipelineCache(pipeline)

    when:
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)

    then:
    matchingPipelines.size() == 1
    matchingPipelines[0].trigger.type == enabledGooglePubsubTrigger.type
    matchingPipelines[0].trigger.pubsubSystem == enabledGooglePubsubTrigger.pubsubSystem
    matchingPipelines[0].trigger.subscriptionName == enabledGooglePubsubTrigger.subscriptionName

    where:
    event = createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", [], [:])
    pipeline = createPipelineWith(goodExpectedArtifacts, enabledGooglePubsubTrigger, disabledGooglePubsubTrigger)
  }

  @Unroll
  def "does not trigger #description pipelines for Google pubsub"() {
    given:
    def pipelines = handlerSupport.pipelineCache(pipeline)

    when:
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)

    then:
    matchingPipelines.size() == 0

    where:
    trigger                                                      | description
    disabledGooglePubsubTrigger                                  | "disabled Google pubsub trigger"
    enabledGooglePubsubTrigger.withSubscriptionName("wrongName") | "different subscription name"
    enabledGooglePubsubTrigger.withPubsubSystem("noogle")        | "different subscription name"

    pipeline = createPipelineWith(goodExpectedArtifacts, trigger)
    event = createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", [], [:])
  }

  @Unroll
  def "does not trigger #description pipelines containing artifacts for Google pubsub"() {
    given:
    def pipelines = handlerSupport.pipelineCache(pipeline)

    when:
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)

    then:
    matchingPipelines.size() == 0

    where:
    trigger                                                                      | description
    enabledGooglePubsubTrigger.withExpectedArtifactIds(badExpectedArtifacts*.id) | "non-matching artifact in message"

    pipeline = createPipelineWith(goodExpectedArtifacts, trigger)
    event = createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", goodArtifacts, [:])
  }

  @Unroll
  def "does not trigger a pipeline that has an enabled pubsub trigger with missing #field"() {
    given:
    def pipelines = handlerSupport.pipelineCache(badPipeline, goodPipeline)

    when:
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)

    then:
    matchingPipelines.size() == 1
    matchingPipelines[0].id == goodPipeline.id

    where:
    trigger                                               | field
    enabledGooglePubsubTrigger.withSubscriptionName(null) | "subscriptionName"
    enabledGooglePubsubTrigger.withPubsubSystem(null)     | "pubsubSystem"

    event = createPubsubEvent(PubsubSystem.GOOGLE, "projects/project/subscriptions/subscription", [], [:])
    goodPipeline = createPipelineWith(goodExpectedArtifacts, enabledGooglePubsubTrigger)
    badPipeline = createPipelineWith(goodExpectedArtifacts, trigger)
  }

  @Unroll
  def "conditionally triggers pipeline on payload constraints"() {
    given:
    def event = new PubsubEvent()

    def description = MessageDescription.builder()
      .pubsubSystem(PubsubSystem.GOOGLE)
      .ackDeadlineSeconds(1)
      .subscriptionName("projects/project/subscriptions/subscription")
      .messagePayload(JsonOutput.toJson([key: 'value']))
      .build()

    def pipeline = createPipelineWith(goodExpectedArtifacts, trigger)
    def pipelines = handlerSupport.pipelineCache(pipeline)

    when:
    def content = new PubsubEvent.Content()
    content.setMessageDescription(description)
    event.payload = [key: 'value']
    event.content = content
    event.details = new Metadata([type: PubsubEventHandler.PUBSUB_TRIGGER_TYPE])
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)

    then:
    matchingPipelines.size() == callCount
    if (callCount > 0) {
      matchingPipelines[0].application == pipeline.application && matchingPipelines[0].name == pipeline.name
    }

    where:
    trigger                                                                | callCount
    enabledGooglePubsubTrigger                                             | 1
    enabledGooglePubsubTrigger.withPayloadConstraints([key: 'value'])      | 1
    enabledGooglePubsubTrigger.withPayloadConstraints([key: 'wrongValue']) | 0
  }

  @Unroll
  def "conditionally triggers pipeline on attribute constraints"() {
    given:
    def event = new PubsubEvent()

    def description = MessageDescription.builder()
      .pubsubSystem(PubsubSystem.GOOGLE)
      .ackDeadlineSeconds(1)
      .subscriptionName("projects/project/subscriptions/subscription")
      .messagePayload(JsonOutput.toJson([key: 'value']))
      .messageAttributes([key: 'value'])
      .build()

    def pipeline = createPipelineWith(goodExpectedArtifacts, trigger)
    def pipelines = handlerSupport.pipelineCache(pipeline)

    when:
    def content = new PubsubEvent.Content()
    content.setMessageDescription(description)
    event.payload = [key: 'value']
    event.content = content
    event.details = new Metadata([type: PubsubEventHandler.PUBSUB_TRIGGER_TYPE, attributes: [key: 'value']])
    def matchingPipelines = eventHandler.getMatchingPipelines(event, pipelines)


    then:
    matchingPipelines.size() == callCount
    if (callCount > 0) {
      matchingPipelines[0].application == pipeline.application && matchingPipelines[0].name == pipeline.name
    }

    where:
    trigger                                                                  | callCount
    enabledGooglePubsubTrigger                                               | 1
    enabledGooglePubsubTrigger.withAttributeConstraints([key: 'value'])      | 1
    enabledGooglePubsubTrigger.withAttributeConstraints([key: 'wrongValue']) | 0
  }
}
