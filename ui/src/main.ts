import { createApp } from "vue";
import "./styles.css";
import { initTheme } from "./api";

initTheme();
const component = new URLSearchParams(window.location.search).has("tray")
  ? import("./components/TrayMenu.vue")
  : import("./App.vue");
void component.then(({ default: view }) => createApp(view).mount("#app"));
