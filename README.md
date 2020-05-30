[![Clojars Project](https://img.shields.io/clojars/v/coreagile/prevayler-clj.svg)](https://clojars.org/coreagile/prevayler-clj)

## Features

If your data fits in RAM, you can write your system as a pure Clojure function, without any database complexity. It will be much simpler, cleaner and orders of magnitude faster.

Prevayler takes care of persistence.

## Usage

- Get enough RAM to hold all your data.
- Implement the business logic of your system as a pure event handling function. Keep any I/O logic (accessing some web service, for example) separate.
- Guarantee persistence by applying all events to you system through Prevayler, like this:

```clojure
(defn my-system [state event]            
  ...)                                   ; Any function returning a pair [new-state event-result].

(with-open [p1 (prevayler! my-system)]
  (assert (= @p1 {}))                    ; The default initial state is an empty map.
  (handle! p1 event1)                    ; Your events can be any Clojure value or Serializable object.
  (handle! p1 event2)
  (assert (= @p1 new-state)))            ; Your system state with the events applied.

(with-open [p2 (prevayler! my-system)]   ; Next time you run,
  (assert (= @p2 new-state)))            ; the state is recovered, even if there was a system crash.
```

## What it Does

Prevayler-clj implements the [system prevalence pattern](http://en.wikipedia.org/wiki/System_Prevalence): it keeps a snapshot of your business system state followed by a journal of events. On startup or crash recovery it reads the last state and reapplies all events since: your system is restored to where it was.

## Shortcomings

- RAM: Requires enough RAM to hold all the data in your system.
- Start-up time: Entire system is read into RAM.

## Files

Prevayler's default file name is `journal` but you can pass in your own file. Prevayler-clj will create and write to it like this:

### journal
Contains the state at the moment your system was last started, followed by all events since. Serialization is done using [Nippy](https://github.com/ptaoussanis/nippy).

### journal.backup
On startup, the journal is renamed to journal.backup and a new journal file is created.
This new journal will only be consistent after the system state has been written to it so when journal.backup exists, it takes precedence over journal.

### journal.backup-[timestamp]
After a new consistent journal is written, journal.backup is renamed with a timestamp appendix. You can keep these old versions elsewhere if you like. Prevayler no longer uses them.

## Transient Mode for Tests
The `transient-prevayler!` function returns a transient prevayler the you can use for fast testing.

## Input/Output interception

You can accomplish encryption-at-rest by calling the 5-parameter form of
`prevayler!`. The last two parameters are functions which take a stream and
and a `java.util.concurrent.locks.ReentrantLock` and
return a stream. The second-to-last function will be invoked with an
`OutputStream` and must return an `OutputStream`, the last function will be
invoked with an `InputStream` and return an `InputStream`. This way you can
use `CipherOutputStream` and `CipherInputStream` to perform encryption-at-rest
of the journal file.

You can probably also do all kinds of other interesting things using an approach
like `org.apache.commons.io.output.TeeOutputStream`. An example of this can
be found in `prevayler.s3/backup-wrapper`, which wraps a stream, sending its
contents to a `ByteArrayOutputStream` which, periodically, is flushed to an
S3 bucket.

The reason this works without weird partial writes is due to the `ReentrantLock`
mentioned above. Whenever we attempt to read or write, we acquire the lock.
That way, if the backup thread needs to get access to data, it can guarantee
it gets a complete set by acquiring the lock.

If you want to make all this work, you'll need to add extra dependencies
to your project:
                                               
``` 
[software.amazon.awssdk/s3 "2.1.0"]
[commons-io/commons-io "2.7"]
```

S3 backup happens in a background thread, which could run up to 1 second behind.

You can see an example of all this working together at the bottom of the 
`prevayler` namespace. 

*Important Note* CipherOutputStream operates quite differently from normal
streams. If you don't close the stream cleanly, it will be completely unusable.
This is because it needs to add padding to the end of the output on close.

So, if your application crashes prematurely and you haven't closed the stream
cleanly, you will be out of luck. The journal will be unusable. One thing
you can do to mitigate this problem is to close the prevayler periodically.
This will cause the streams to be padded properly. It will also have the 
added side effect of generating a lot of journal backup files.

Another thing you can do is not rely on CipherOutputStream at all. Use 
disk-based encryption for local disk, and bucket encryption for your S3 backups.

---

"Simplicity is prerequisite for reliability." Dijkstra (1970)
