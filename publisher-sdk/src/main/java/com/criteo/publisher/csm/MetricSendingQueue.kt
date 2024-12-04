/*
 *    Copyright 2020 Criteo
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.criteo.publisher.csm

// this interface serves as marker interface for dependency injection
internal interface MetricSendingQueue : ConcurrentSendingQueue<Metric> {
  class AdapterMetricSendingQueue(private val delegate: ConcurrentSendingQueue<Metric>) : MetricSendingQueue {
    override fun offer(element: Metric) = delegate.offer(element)

    override fun poll(max: Int): List<Metric> = delegate.poll(max)

    override val totalSize: Int
      get() = delegate.totalSize
  }
}
