// maumm.org 공용 서버 연결 (Firebase). 화면은 window.MC 만 씁니다.
// 설정(FIREBASE_CONFIG)이 없으면 MC.on = false 이고, 앱은 휴대폰 저장으로만 동작합니다.
import { initializeApp } from "firebase/app";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  signOut, sendPasswordResetEmail,
} from "firebase/auth";
import {
  initializeFirestore, persistentLocalCache, doc, getDoc, setDoc, deleteDoc, collection,
  onSnapshot, writeBatch,
} from "firebase/firestore";
import { FIREBASE_CONFIG } from "./config.js";

const clean = d => JSON.parse(JSON.stringify(d ?? null));
const ERR = {
  "auth/invalid-credential": "이메일 또는 비밀번호가 맞지 않아요",
  "auth/wrong-password": "비밀번호가 맞지 않아요",
  "auth/user-not-found": "가입되지 않은 이메일이에요",
  "auth/email-already-in-use": "이미 가입된 이메일이에요 — 로그인해 주세요",
  "auth/weak-password": "비밀번호는 6자 이상이어야 해요",
  "auth/invalid-email": "이메일 형식이 맞지 않아요",
  "auth/too-many-requests": "시도가 너무 많아요 — 잠시 뒤 다시 해 주세요",
  "auth/network-request-failed": "인터넷 연결을 확인해 주세요",
  "auth/configuration-not-found": "서버에서 이메일 로그인이 아직 켜져 있지 않아요",
  "permission-denied": "권한이 없어요",
  "unavailable": "인터넷 연결을 확인해 주세요",
};

function make(config) {
  const app = initializeApp(config);
  const auth = getAuth(app);
  let db;
  try { db = initializeFirestore(app, { localCache: persistentLocalCache() }); }
  catch (e) { db = initializeFirestore(app, {}); }
  const R = p => doc(db, ...p.split("/"));
  const C = p => collection(db, ...p.split("/"));
  let user = null;
  const ready = new Promise(res => { const u = onAuthStateChanged(auth, x => { user = x; u(); res(x); }); });
  return {
    on: true,
    ready,
    get user() { return auth.currentUser ? { uid: auth.currentUser.uid, email: auth.currentUser.email || "" } : null; },
    onAuth: cb => onAuthStateChanged(auth, x => cb(x ? { uid: x.uid, email: x.email || "" } : null)),
    signIn: (e, p) => signInWithEmailAndPassword(auth, e.trim(), p),
    signUp: (e, p) => createUserWithEmailAndPassword(auth, e.trim(), p),
    signOut: () => signOut(auth),
    reset: e => sendPasswordResetEmail(auth, e.trim()),
    async get(path) { const s = await getDoc(R(path)); return s.exists() ? s.data() : null; },
    set: (path, data, merge) => setDoc(R(path), clean(data), merge ? { merge: true } : {}),
    del: path => deleteDoc(R(path)),
    onDoc: (path, cb, err) => onSnapshot(R(path), s => cb(s.exists() ? s.data() : null, s), err),
    onCol: (path, cb, err) => onSnapshot(C(path), { includeMetadataChanges: false }, cb, err),
    newId: path => doc(C(path)).id,
    async batchSet(items) { // [[path, data], ...]
      for (let i = 0; i < items.length; i += 400) {
        const b = writeBatch(db);
        items.slice(i, i + 400).forEach(([p, d]) => b.set(R(p), clean(d)));
        await b.commit();
      }
    },
    err: e => { const c = (e && e.code) || ""; return (ERR[c] || (e && e.message) || "문제가 생겼어요") + (c ? ` (${c})` : ""); },
  };
}
window.MC = FIREBASE_CONFIG ? make(FIREBASE_CONFIG) : { on: false, err: e => String(e && e.message || e) };
