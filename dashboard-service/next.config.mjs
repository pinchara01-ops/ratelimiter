/** @type {import('next').NextConfig} */
const nextConfig = {
  experimental: {
    // Allow server components to import gRPC modules (Next.js 14 option)
    serverComponentsExternalPackages: ["@grpc/grpc-js", "@grpc/proto-loader"],
  },
};

export default nextConfig;
