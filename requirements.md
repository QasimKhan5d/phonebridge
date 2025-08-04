We have an app in "/Users/qasim.khan/Documents/gemma3nImpactChallenge/braille-bridge/teacher_web" that can export the following things

1. Lesson pack
2. Student feedback

Lesson pack is a zip containing folders "item_N" from 1 to number of questions. Each folder contains the following:

- Science diagram "XXX.png" where XXX is a number
- "question.txt" which contains the question that the student needs to answer using the diagram
- "audio_en.wav" which is an audio description of the diagram in english
- "script_en.txt" which is the transcript of the audio
- "script_ur.txt" which is the urdu translation of the audio
- "braille_en.svg" and "braille_ur.svg" which are braille renderings of the above scripts
- "diagram.json" which will be used as context to the LLM to answer the student's questions about the diagram

Student feedback includes the following
- Text describing the teacher's feedback with regards to the student's answer to a question in the lesson pack
- A braille SVG containing red underline to highlight what the student got wrong in case their answer was wrong.

What we will do is I will manually upload these to the files folder of the braille-bridge app.

## Homepage
The app should detect if new homework is found or new feedback is found in its storage. When the user opens the app it says, "you have new homework, tap once to open it" and/or "you have new feedback, tap twice to open it" depending on whether the homework and feedback are in the storage. If nothing is found, just speak you have no new notifications.

## Homework

Now assume that the user of this app is either visually impaired (can only read braille) or blind (can only use listen and respond with audio).
If the user opens lesson pack, the app displays the homework from the first folder. The screen shows the following

1. Diagram at the top.
2. Question
3. Braille rendering (in English)

From here on, there are various options that need to be provided to the user:

1. If they are ready to respond to the question, they should be allowed to either respond by uploading an image of their homework or to speak the answer (which will be saved as audio file).
2. They should be given the option to listen to the audio narration.
3. They should be given the option to switch to Urdu, which will change the Braille rendering to use the Urdu version. In the next turn, if the user asks to listen to the audio narration, you need to TTS the urdu translation of the script.
4. They should be allowed to ask clarifying questions by speaking. What they say will get STT'd, and then given to Gemma3N. Gemma3N will use the text inside "diagram.json" to answer the question without revealing the answer to the question.

Once they are ready to submit, we go to the next question. If there are no more questions, go back to the homepage.

You need to implement this finite state automata with robust state management.

## Feedback

A feedback is a zip file containing 1 or 2 files:

1. Feedback text file (compulsory) written as "feedback_submission_X.txt" where X is some number
2. Braille svg file (optional) containing the student's rendered braille submission with a red underline where their answer is wrong.

Example is shown in "/Users/qasim.khan/Documents/gemma3nImpactChallenge/braille-bridge/submission_1_feedback/"

If feedback is available, double tap on the homepage to open the feedback page. The feedback page opens the first feedback item and the UI shows:

1. The feedback text
2. The braille rendering (if available)

The app speaks the feedback to the user in the current language using TTS. Hold and release and then speak "switch" to switch the language to Urdu.
The app then translates the feedback text to Urdu and speaks it. This works exactly as the homework page but with less features.
Holding and releasing has 3 options:

1. Repeat to repeat the feedback (in the current language)
2. Switch to switch the language
3. Ask for feedback understanding

Goals of feedback understanding are underlined below:


✅ 1. Feedback Understanding Assistant (Core Feature)
Gemma helps the student understand what the teacher meant, and how to improve.

🎯 Problem it solves:
Teacher feedback is often vague, abrupt, or assumes background knowledge the student doesn’t have.

A child might hear: “Rephrase this with better grammar,” or “You misunderstood the diagram,” and not know what to do.

💡 What the assistant does:
Takes the teacher's feedback (text + audio if needed)

Takes the original question and student answer

Acts as a private tutor to explain:

What the teacher meant

What the issue was

How to fix it in future

Simple re-teaching of the missed concept

📦 Example interaction:
Teacher’s feedback: “Your answer was too vague and didn’t explain evaporation.”

Student says: “What did I miss?”

Gemma responds (spoken): “The teacher wanted you to explain what evaporation is. Let’s review that. Evaporation means...”

✅ On-device Gemma needed:
Handles long context: original Q, answer, and feedback

Outputs simplified, spoken explanation

🔁 Optionally:
Let student ask follow-ups: “Can you give me an example?” or “How do I write that better?”

To do the above, you again need to provide context to the model. To do this, you look up the "diagram.json" in the corresponding homework folder. The corresponding folder is identified by the "X" in the folder name. The homework follows "item_X" and the feedback follows "submission_X_feedback". Write a good prompt to achieve the above stated goals.

## Verbal Spatial Grounding Assistant (VSGA) (final feature to be done at the end)

“Child places finger on the diagram. Teacher app photographs it. Gemma explains what they’re touching, where it is, and how it relates to the rest of the diagram — in spoken Urdu. All offline. All on-device.”

🧠 Problem It Solves
Blind kids using tactile diagrams can feel parts, but:

They don’t always know what they’re touching

They can’t form a global spatial model (e.g., “this is above the river and next to the hill”)

Verbal assistance from teachers is inconsistent, slow, or unavailable

📱 How It Works (Teacher App Feature)
Teacher holds phone/laptop camera over the tactile diagram

Child places their finger on the diagram

App captures image:

Diagram

Finger position

Gemma 3n (finetuned or prompted multimodally) does:

Object localization

Spatial relation inference

Natural Urdu explanation

🧠 Output Example (Spoken or TTS)
“You’re touching the cloud. It’s at the top-left corner of the diagram. Below it is an arrow pointing to rain. To the right is the Sun. This part shows evaporation.”