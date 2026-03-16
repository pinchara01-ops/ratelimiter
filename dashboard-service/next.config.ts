import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Allow server components to import gRPC modules
  serverExternalPackages: ["@grpc/grpc-js", "@grpc/proto-loader"],
};

export default nextConfig;
