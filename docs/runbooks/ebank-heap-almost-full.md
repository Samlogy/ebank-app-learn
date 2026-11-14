# EbankHeapAlmostFull

**Meaning.** JVM heap use has been above 90% of its maximum for 10 minutes.

**Impact.** Long GC pauses (slow requests), then `OutOfMemoryError` or an `OOMKilled` pod.

**Check.**
1. Steady climb or saw-tooth? Graph `jvm_memory_used_bytes{area="heap"}` over 6 h. A climb that never drops after GC suggests a leak.
2. The heap is 75% of the container memory limit (`-XX:MaxRAMPercentage=75`). Compare with the pod's limit: `backend.resources.limits.memory` in the Helm values.
3. A heap dump, if a leak is suspected: `jcmd 1 GC.heap_dump /tmp/heap.hprof` in the container, then copy it out (`kubectl cp`). It contains customer data: handle it as such and delete it after use.

**Fix.** Short term: restart the backend. Lasting: raise the memory limit in the environment's values file, or fix the leak.

**Escalate.** A leak: a backend developer, with the heap dump.
