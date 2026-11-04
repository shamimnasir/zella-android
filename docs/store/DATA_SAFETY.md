# Data safety form: proposed answers

The brief's instruction was to answer honestly: no data collected, no data shared, with the optional network
paths disclosed. There is one decision here that needs the owner, because Google's definitions are narrower
than that sentence.

## What the app actually does (checked in the code on 2026-11-04)

| Path | What leaves the phone | To whom | When |
| --- | --- | --- | --- |
| Assistant | What the person types, a short text summary of the project with caption text, tool results as text, including any on-screen text the person asks it to read (secret-looking values masked). Never video, sound or file paths (`AssistantTest`) | The provider the person picked, with their own key | Only when they send a message |
| Stock B-roll | A search word and the person's own key; then the clip is downloaded (`BRollRenderTest`) | Pexels or Pixabay | Only when they tap Add stock clips, or ask the Assistant |
| Caption models | A download request, no content | models.zellahq.com | Only when they tap download |
| Play Billing | Whatever Google Play Billing sends, handled by Google | Google | When buying or restoring, and a read of purchases at start |

Zella has no server, no account, no analytics and no crash reporting. Google's own Play services may report on
their own account of things; that is outside the app.

## Proposed answers

- Does your app collect or share any of the required user data types? **See the decision below.**
- Is all of the user data collected by your app encrypted in transit? **Yes.** HTTPS only: `HttpsTransport` and
  `HttpsStock` refuse anything else. Android's cleartext default is also off at target SDK 28 and up.
- Do you provide a way for users to request that their data be deleted? **Nothing is stored off the phone.**
  Deleting a project or uninstalling removes it. An Assistant provider or stock site keeps what its own policy says.

## The decision for the owner

Google counts data as "collected" when it is transmitted off the device, with exceptions for on-device
processing. Its definition of "shared" excludes transfers the user starts knowingly. The Assistant's text goes to
a third party the person chose and asked for, on each message, and Zella never receives it. Reasonable people
read that two ways:

1. **Declare nothing** ("No data collected"), on the reasoning that Zella itself collects nothing and each send is
   the person's own act with their own key, labelled on screen. This is what the brief asks for.
2. **Declare it**: Other in-app messages and Other user-generated content, not shared, optional, "App functionality".
   This is the cautious reading, and an audit could not fault it.

Recommendation: option 2. It costs little and matches "honest copy" better, since caption text does leave the
phone when the Assistant is used. Check Google's current wording before submitting, because this was written
from memory of the form and has not been read against the live Play Console.

## Other forms

- Content rating questionnaire: a utility and editor. No violence, sex, gambling or user-to-user sharing in the
  app. Sharing goes through the system share sheet.
- Target audience: 13 and up is a sensible start. The owner decides.
- Ads: none. In-app purchases: yes, two one-time products.
- Permissions: `docs/store/PERMISSIONS.md` gives a reason for each one, for the Play declaration forms.
