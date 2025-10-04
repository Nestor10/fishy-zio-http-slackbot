# Testing Guide - MessageStore Integration

## How to Test MessageStore

The MessageStore is now integrated into the application and will automatically store Thread objects when AppMention events are received. Here's how to verify it's working:

### What to Look For in Logs

When the app is running and receives an AppMention event, you should see the following log sequence:

1. **AppMention Received (creates new thread):**
   ```
   📢 APP_MENTION: envelope=xxx user=U123 channel=C456 thread=new
   📢 APP_MENTION_TEXT: @bot hello there
   ```

2. **Thread Created:**
   ```
   🆕 NEW_THREAD_CREATED: id=1234567890.123456 channel=C456
   ```

3. **Storage Operations:**
   ```
   💾 THREAD_STORED: id=1234567890.123456 messages=1
   ✅ STORAGE_VERIFIED: Retrieved thread 1234567890.123456 with 1 messages
   ```

4. **Thread Reply Received (MVP feature):**
   ```
   🧵 THREAD_REPLY: envelope=yyy user=U456 channel=C456
   💬 MESSAGE_TEXT: thanks for the help!
   🧵 THREAD_TS: 1234567890.123456
   💾 THREAD_REPLY_STORED: thread=1234567890.123456 message=1234567891.234567 author=U456
   ```

5. **Periodic Stats (every 60 seconds):**
   ```
   📊 STORAGE_STATS: messages=2 threads=1
   ```
   Note: Message count increments with each thread reply!

### Error Cases

If storage fails, you'll see:
```
❌ FAILED_TO_STORE_THREAD: <error message>
⚠️ STORAGE_VERIFICATION_FAILED: <error message>
```

The app will continue processing messages even if storage fails (resilient design).

### Running the App

```bash
# Start the app
sbt run

# Or in dev mode (auto-reload)
sbt dev
```

### Triggering an AppMention

1. Add the bot to a Slack channel
2. Mention the bot in a **new** message (not in a reply to an existing thread):
   ```
   @your-bot-name hello!
   ```
3. Watch the logs for the thread creation sequence above
4. **MVP Feature**: Reply in the same thread:
   ```
   (in the thread) thanks!
   ```
5. Watch the logs for the thread reply storage:
   ```
   🧵 THREAD_REPLY: envelope=yyy user=U456 channel=C456
   💾 THREAD_REPLY_STORED: thread=1234567890.123456 message=1234567891.234567 author=U456
   ```

### Expected Behavior

- **New thread mentions**: Should create Thread object, store it, verify storage
- **Existing thread replies**: Will log `❌ THREAD_MENTION_DISCARDED: mention in existing thread`
- **Thread replies (any user)**: **MVP: Now stored!** - Will log `💾 THREAD_REPLY_STORED`
- **Non-tracked thread replies**: Will log at DEBUG level `🔍 THREAD_NOT_TRACKED` (thread not initiated by bot)
- **Stats**: Should increment with each new thread AND each reply
- **Stats Logger**: Runs every 60 seconds showing current counts

### Storage Contents

The InMemory storage maintains:
- `messages: Map[MessageId, ThreadMessage]` - All individual messages
- `threads: Map[ThreadId, Thread]` - Complete threads with all messages

When a Thread is stored via `storeThread(thread)`:
1. The Thread is added to the threads map
2. All messages in the thread are added to the messages map
3. Both the thread and its messages are retrievable

**MVP Feature**: When a thread reply is stored via `store(message)`:
1. The ThreadMessage is added to the messages map
2. It's linked to the existing Thread via `threadId`
3. The message is retrievable individually or as part of the thread

Note: The Thread object in the threads map is NOT automatically updated with new replies.
This is intentional for Phase 1 - the Thread represents the initial state.
Future phases will add thread state updates and event broadcasting.

### Verification Steps

To confirm MessageStore is working:

1. ✅ **Compilation**: `sbt compile` succeeds
2. ✅ **App Starts**: See `🚀 APP: Application starting...` and `MessageStore.InMemory initialized`
3. ✅ **Stats Running**: See `📊 STORAGE_STATS: messages=0 threads=0` every 60 seconds
4. ✅ **AppMention Processing**: Mention bot in Slack, see the full log sequence above
5. ✅ **Stats Updated**: See stats increment after storing thread
6. ✅ **MVP: Thread Reply Storage**: Reply in the thread, see `💾 THREAD_REPLY_STORED`
7. ✅ **MVP: Message Count Increments**: Stats should show messages increasing with each reply

### Debug Mode

To see more detailed storage logs, you can:

1. Enable debug logging in `logback.xml`:
   ```xml
   <logger name="slacksocket.demo.service.MessageStore" level="DEBUG"/>
   ```

2. You'll then see:
   ```
   Stored message: <MessageId>
   Stored thread: <ThreadId> with N messages
   ```

### Next Steps (Phase 2)

Once you've verified MessageStore is working:
- [ ] Move to Phase 2: MessageEventBus (Hub-based pub/sub)
- [ ] Subscribe to Thread events for real-time processing
- [ ] Implement event broadcasting to multiple consumers
