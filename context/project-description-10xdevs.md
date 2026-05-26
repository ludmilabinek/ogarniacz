# Project Idea — 10xdevs 3.0 Final Project

> Working product description. No technology — only the idea, the user, the problem, the value.

---

## 1. Problem

The parent of a small child lives in constant fear of missing something: *"tomorrow there's a field trip, need to pack a sandwich"*, *"on Tuesday a yellow shirt"*, *"by Friday bring 5 PLN for the puppet show"*. Kindergarten announcements pop up in many places at once: notes on the corridor wall, posts in Messenger/WhatsApp groups, screenshots circulating between parents, announcements on the kindergarten's website, posts in the kindergarten's app (LiveKid). Each of these sources has to be read, understood for **what it actually implies** (date, time, what to bring, how to dress the child), and transferred into the parent's memory system — a calendar, a list, their head. Usually it ends up in "their head", which fails them.

The same pattern repeats throughout adult life beyond kindergarten: vaccination appointments, services, invoices, bills, payment deadlines. Same mental cost everywhere: **turning unstructured information into a concrete event with a time and an action**.

## 2. User

**Primary persona:** the parent of a kindergarten-aged child, using a digital calendar (Google Calendar / Apple), who wants all child-related obligations in one trusted place — without manual rewriting.

In the MVP, the user is one specific person (the project's author herself), with the prospect of expanding to other parents. Personal use as a starting point, not as a product limitation.

## 3. What the app does (first module — kindergarten)

**Main user flow:**

1. The parent sees an announcement in the kindergarten and takes a photo with their phone, or takes a screenshot (from a Messenger/WhatsApp group, the kindergarten's website, or the LiveKid app).
2. They upload the image to the app.
3. The app reads the text from the image and recognizes **what follows from it** — which events, on which dates, with which details (what to bring, how to dress the child, at what time).
4. The app presents the parent with proposed events as an editable list. Each event has a date, time (if it was specified), title, what to bring, dress code, and a space for notes.
5. The parent reviews the proposals, edits what needs editing (e.g. clarifies the time), accepts, rejects. Every event has a **reminder set to the day before by default** — the parent can change it (e.g. to 2 hours before, if they want more time for morning preparations), but doesn't need to click anything to have a reminder.
6. Once approved, events go into the parent's personal view in the app **and automatically appear in the parent's Google Calendar**, thanks to calendar subscription (`.ics` URL) — configured once for the lifetime of the project.

**Calendar synchronization:**
- On first use, the app generates a unique `.ics` URL for the parent (with an access token).
- The parent pastes this URL into Google Calendar once ("Other calendars" → "From URL").
- From then on, Google Calendar automatically polls the app every several hours and synchronizes the accepted events.
- Nothing to import manually. You accept in the app, within at most a day it's in the calendar.
- The same URL can be shared with a spouse — both subscribe, both see the same events.

**Manual fallback:** if there is no image (e.g. a verbal announcement from the teacher), an event can be added manually — the same form, just without the extraction step.

## 4. Extraction quality control

Extraction from photographs is inherently imperfect: the photo may be blurry, cropped, glared, illegible. The app **does not pretend it succeeded** when it didn't.

Three levels:
- **All OK** — events presented normally, recognition confidence is high.
- **Low quality** — events are presented, but some fields are marked as uncertain (e.g. a yellow "?" badge), the app asks for careful review.
- **Unreadable** — direct message: *"Could not read this image. Please try again with a better photo, or add the event manually."* The parent can try a better shot or add the event manually.

**Overriding rule:** no event enters the list or the calendar without explicit acceptance from the parent. AI proposes, the human decides.

## 5. What the app does NOT do (intentional exclusions from the first stage)

- No mobile app — the website also works on a phone (photos from the phone's camera via the browser, screenshots uploaded normally).
- Does not handle calendar sharing between parents at the application level — Google Calendar already does that (a shared calendar between spouses).
- Does not rigidly categorize events (kindergarten / home / work) — the first module is only about kindergarten.
- Does not process voice notes or invoices — those are future modules.

## 6. Long-term vision

The app is the first module of a broader idea: **an assistant for organizing the chores of adult life** — all those *"I have to remember about…"* items that today end up on sticky notes, in Messenger threads, in the parent's head.

Future modules follow the same pattern *unstructured input → event with time and action*:

- **Voice notes → events.** *"Schedule a vaccination", "call the plumber about the invoice"* — a quick recording, the app recognizes the action and the date, adds a reminder.
- **Invoices → payment reminders.** A photo of an invoice, recognition of the amount and the due date, reminder a day before.
- **Other announcement sources** — school, medical, administrative.

The first module (kindergarten) is intentionally narrow. The app's architecture, however, should allow adding further modules without rewriting the rest.

## 7. Value for the user

- **Time and attention savings:** 5-30 seconds per announcement instead of 3-5 minutes of manual rewriting into the calendar.
- **Confidence that nothing slips through:** kindergarten obligations land in the same calendar where work meetings and doctor visits already are.
- **Low barrier to entry:** all you have to do is take a photo. You don't need to remember about the app while reading the announcement — you just need to have it available when needed.

## 8. Product success (MVP criteria)

The app works if the parent:
1. Uploads a photo/screenshot of a real kindergarten announcement and receives proposed events that are **mostly correct** (date, time, what to bring).
2. Within 1 minute of uploading the image, can accept the corrected proposals.
3. After a one-time calendar subscription setup (once in their lifetime), sees the accepted events in their Google Calendar — without any additional actions.

## 9. Name candidates (open for discussion)

**Polish:**
- *Ogarniam* — a verb, scales best to future modules
- *Dorośleko* — diminutive of "adult", self-ironic
- *Pamiętam!* — most clearly communicates the function
- *Spokój Mam* — pun on "mam = mom" + "I have peace"

**English:**
- *Life Admin* — describes the entire umbrella idea
- *Adulting* — pop-culture reference, self-ironic
- *Handled* — short, satisfying

Final decision after the technical design is refined.

---

*Working document. Status: skeleton, to be iterated.*
