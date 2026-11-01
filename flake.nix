{
  description = "Dapla pseudo service";

  inputs = {
    flake-parts.url = "github:hercules-ci/flake-parts";
    nixpkgs.url = "https://channels.nixos.org/nixpkgs-unstable/nixexprs.tar.zst";
  };

  outputs = inputs @ {flake-parts, ...}:
    flake-parts.lib.mkFlake {inherit inputs;} {
      systems = ["x86_64-linux" "aarch64-linux" "aarch64-darwin"];
      perSystem = {
        pkgs,
        ...
      }: {
        devShells.default = pkgs.mkShell {
          name = "dapla-pseudo-service";
          packages = with pkgs; [
            gnumake
            nixd
            maven
            openjdk25
            trivy
            yaml-language-server
          ];
        };
        formatter = pkgs.alejandra;
      };
    };
}
