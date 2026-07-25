import axios from "axios";

export const api = axios.create({
  baseURL: "http://localhost:8082/api/v1",
});
